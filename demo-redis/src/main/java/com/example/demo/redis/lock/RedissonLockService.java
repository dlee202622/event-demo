package com.example.demo.redis.lock;

import com.example.demo.redis.lock.LockResults.CounterResult;
import com.example.demo.redis.lock.LockResults.Event;
import com.example.demo.redis.lock.LockResults.RedissonLockResult;
import com.example.demo.redis.lock.LockResults.TtlSample;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redisson 锁：对比「不指定 leaseTime（看门狗续期）」和「指定 leaseTime（不续期）」。
 *
 * <p>Redisson 的默认行为：加锁 TTL 30 秒，后台看门狗每 10 秒（TTL/3）续一次，
 * 只要持有线程还活着，锁就不会过期。一旦调用
 * {@code tryLock(wait, leaseTime, unit)} 显式指定 leaseTime，看门狗会被关闭，
 * 锁到点即失效——这是最常见的误用。</p>
 *
 * <p>本实验用 TTL 采样观察续期：TTL 从约 20 秒跳回约 30 秒，说明看门狗工作；
 * 若 TTL 单调递减到 0，说明锁即将在业务结束前失效。</p>
 */
@Service
public class RedissonLockService {

    /** Redisson 实验的 key 前缀，与手写锁隔离。 */
    private static final String KEY_PREFIX = "demo:lock:redisson:";

    private final RedissonClient redisson;
    private final StringRedisTemplate redis;

    /** 注入 Redisson 客户端；模板只用于采样 TTL 和清理实验 key。 */
    public RedissonLockService(RedissonClient redisson, StringRedisTemplate redis) {
        this.redisson = redisson;
        this.redis = redis;
    }

    /**
     * A 持锁 holdSeconds；B 在 leaseSeconds + 1 秒（或 holdSeconds - 3 秒）时尝试抢锁。
     *
     * <p>两个分支：</p>
     * <ul>
     *   <li>leaseSeconds=null：看门狗生效，B 在 A 结束前抢不到，overlap=false；</li>
     *   <li>leaseSeconds 有值：锁在 leaseSeconds 后过期，B 能提前进入，overlap=true。</li>
     * </ul>
     *
     * @param name         key 后缀
     * @param holdSeconds  A 模拟业务耗时（秒）
     * @param leaseSeconds 显式租约（秒）；null 表示交给看门狗
     * @return 事件时间线、TTL 采样、是否重叠、是否观察到续期
     */
    public RedissonLockResult hold(String name, long holdSeconds, Long leaseSeconds) {
        String key = KEY_PREFIX + name;
        // 清掉上一轮可能残留的锁，保证实验从空状态开始
        redis.delete(key);

        // A/B 线程和采样任务都会写，使用线程安全集合
        List<Event> events = Collections.synchronizedList(new ArrayList<>());
        List<TtlSample> samples = Collections.synchronizedList(new ArrayList<>());
        long start = System.currentTimeMillis();
        AtomicBoolean aHolding = new AtomicBoolean(false);
        AtomicBoolean overlap = new AtomicBoolean(false);
        CountDownLatch aAcquired = new CountDownLatch(1);
        CountDownLatch aFinished = new CountDownLatch(1);
        // 采样是低频任务，单线程足够；避免与业务线程争抢
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        Thread threadA = new Thread(() -> {
            RLock lockA = redisson.getLock(key);
            boolean acquired;
            try {
                // 两参数 tryLock：等待最多 5 秒，不指定 leaseTime -> 看门狗接管续期
                // 三参数 tryLock：leaseTime 一到就释放，看门狗不会再续
                acquired = leaseSeconds == null
                        ? lockA.tryLock(5, TimeUnit.SECONDS)
                        : lockA.tryLock(5, leaseSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // Redisson 4 的 tryLock 会抛受检中断异常；恢复标记并视为未获取
                Thread.currentThread().interrupt();
                acquired = false;
            }
            events.add(new Event(elapsed(start), "A", "tryLock", acquired,
                    leaseSeconds == null ? "未指定 leaseTime，看门狗生效" : "leaseTime=" + leaseSeconds + "s"));
            aAcquired.countDown();
            if (!acquired) {
                aFinished.countDown();
                return;
            }
            aHolding.set(true);
            // 模拟业务执行；是否超过租约由 holdSeconds 与 leaseSeconds 的差值决定
            sleep(holdSeconds * 1000);
            try {
                lockA.unlock();
                events.add(new Event(elapsed(start), "A", "unlock", true, "释放成功"));
            } catch (IllegalMonitorStateException e) {
                // 锁已过期并被 B 拿走时，A 不再是持有者，unlock 会抛此异常。
                // 这不是 bug，而是「业务超时后失去锁」的直接证据。
                events.add(new Event(elapsed(start), "A", "unlock", false,
                        "锁已过期或被 B 持有，A 已经无权释放"));
            } finally {
                aHolding.set(false);
                aFinished.countDown();
            }
        }, "redisson-a");
        threadA.start();
        await(aAcquired, 3000);

        // 每 500ms 采样一次 TTL：Redisson 续期是 10 秒级，500ms 足以看到跳变
        ScheduledFuture<?> sampler = scheduler.scheduleAtFixedRate(() -> {
            if (!aHolding.get()) {
                return;
            }
            Long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
            samples.add(new TtlSample(elapsed(start), ttl == null ? -2 : ttl));
        }, 0, 500, TimeUnit.MILLISECONDS);

        // 触发时间：
        // - 显式租约：租约结束后 1 秒，确保锁已过期，B 必然能进；
        // - 看门狗：A 结束前 3 秒，此时锁仍被续着，B 应该抢不到。
        long triggerSeconds = leaseSeconds != null
                ? leaseSeconds + 1
                : Math.max(2, holdSeconds - 3);
        Thread threadB = new Thread(() -> {
            sleep(triggerSeconds * 1000);
            RLock lockB = redisson.getLock(key);
            boolean acquired;
            try {
                // B 只等 1 秒：实验关注「此刻能否拿到」，不关注排队
                acquired = lockB.tryLock(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                acquired = false;
            }
            if (acquired && aHolding.get()) {
                // A 还在执行，B 却拿到锁：临界区重叠，锁的保护已失效
                overlap.set(true);
            }
            events.add(new Event(elapsed(start), "B", "tryLock", acquired,
                    acquired ? "B 进入临界区，A 仍在执行" : "锁仍被 A 持有"));
            if (acquired) {
                // 停留 500ms，让重叠在时间线/采样上可见
                sleep(500);
                lockB.unlock();
                events.add(new Event(elapsed(start), "B", "unlock", true, "B 释放"));
            } else if (leaseSeconds == null) {
                // 看门狗场景：B 这次没抢到，等 A 正常释放后再验证锁可被获取
                await(aFinished, holdSeconds * 1000);
                boolean retried;
                try {
                    retried = redisson.getLock(key).tryLock(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    retried = false;
                }
                events.add(new Event(elapsed(start), "B", "retryLock", retried,
                        retried ? "A 释放后 B 获取成功" : "仍未获取"));
                if (retried) {
                    redisson.getLock(key).unlock();
                }
            }
        }, "redisson-b");
        threadB.start();

        // 等 A 收尾（含异常路径），再多留 3 秒给 B 的重试，然后关闭调度器
        await(aFinished, (holdSeconds + 3) * 1000);
        sampler.cancel(false);
        scheduler.shutdownNow();
        redis.delete(key);

        // 先从 TTL 采样推断看门狗是否真的续过期；不依赖日志，结果可被接口断言
        boolean watchdogRenewed = false;
        // 看门狗每次续期会把 TTL 拉回 30 秒：相邻采样跳升 >1 秒即可判定续期发生；
        // 阈值取 1 秒是为了过滤采样抖动造成的自然波动
        for (int i = 1; i < samples.size(); i++) {
            if (samples.get(i).ttlMillis() > samples.get(i - 1).ttlMillis() + 1000) {
                watchdogRenewed = true;
                break;
            }
        }
        events.sort(Comparator.comparingLong(Event::atMillis));
        return new RedissonLockResult(leaseSeconds == null ? "redisson-watchdog" : "redisson-lease",
                key, holdSeconds, leaseSeconds, overlap.get(), watchdogRenewed,
                List.copyOf(events), List.copyOf(samples));
    }

    /**
     * 并发计数实验：多个线程各自把共享计数 +1（读 → sleep → 写）。
     *
     * <p>故意在读写之间 sleep 1ms，把竞态窗口拉长：不加锁时多个线程会读到同一个旧值，
     * 后写的覆盖先写的，最终 actual 明显小于 expected。加 Redisson 锁后每次自增
     * 都在临界区内完成，结果应精确等于 expected。</p>
     *
     * @param locked     是否对每次自增加锁
     * @param threads    线程数
     * @param iterations 每个线程的自增次数
     * @return 期望值、实际值、丢失量、总耗时
     */
    public CounterResult counter(boolean locked, int threads, int iterations) {
        // 普通 int 数组不是线程安全的：这里就是要暴露「不加锁丢更新」
        int[] value = {0};
        // 每轮用随机 key，避免不同实验之间互相影响
        String key = "demo:lock:counter:" + UUID.randomUUID();
        long start = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                RLock lock = redisson.getLock(key);
                try {
                    for (int i = 0; i < iterations; i++) {
                        if (locked) {
                            // 锁粒度是「单次自增」：包住整个循环会退化成串行，
                            // 虽然也正确，但观察不到锁的获取/释放开销
                            lock.lock();
                        }
                        try {
                            int current = value[0];
                            // 放大读-改-写之间的窗口，让竞态稳定出现
                            sleep(1);
                            value[0] = current + 1;
                        } finally {
                            // 只释放当前线程持有的锁；若锁已因超时失效，unlock 会抛异常
                            if (locked && lock.isHeldByCurrentThread()) {
                                lock.unlock();
                            }
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        // 超时按最坏情况估算：每次迭代最多约 50ms，再留 30 秒余量
        await(done, (long) threads * iterations * 50 + 30_000);
        pool.shutdownNow();

        long expected = (long) threads * iterations;
        long actual = value[0];
        return new CounterResult(locked, threads, iterations, expected, actual,
                expected - actual, System.currentTimeMillis() - start);
    }

    /** 相对实验开始的毫秒数，用于事件排序。 */
    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    /** 演示用 sleep：被中断时恢复中断标记。 */
    private static void sleep(long millis) {
        try {
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 带超时等待，防止实验因异常永久挂起。 */
    private static void await(CountDownLatch latch, long timeoutMillis) {
        try {
            latch.await(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
