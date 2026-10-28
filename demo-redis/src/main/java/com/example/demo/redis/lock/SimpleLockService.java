package com.example.demo.redis.lock;

import com.example.demo.redis.lock.LockResults.Event;
import com.example.demo.redis.lock.LockResults.SimpleLockResult;
import com.example.demo.redis.lock.LockResults.TtlSample;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 手写锁的两个实验：不续期导致锁提前过期，以及手写看门狗定时续期。
 *
 * <p>固定时序（以方法开始为 t=0）：</p>
 * <pre>
 * t=0            A 尝试加锁，成功后执行 holdMillis
 * t=ttl+300ms    B 尝试加锁：此时不续期的话锁已过期，B 能进来
 * t=holdMillis   A 解锁：如果锁已过期，Lua 校验失败，A 无法释放 B 的锁
 * </pre>
 *
 * <p>实验会同时采样锁的 TTL，用 {@code watchdogRenewed} 和 {@code overlap} 两个布尔值
 * 把「续期是否发生」「临界区是否重叠」变成可断言的结果。</p>
 */
@Service
public class SimpleLockService {

    /** 手写锁 key 前缀；与 Redisson 实验分开，避免互相干扰。 */
    private static final String KEY_PREFIX = "demo:lock:simple:";

    /** 手写锁实现；实验只通过它加锁、续期、解锁。 */
    private final SimpleRedisLock lock;
    /** 只用于删除实验 key 和采样 TTL，不直接参与加锁逻辑。 */
    private final StringRedisTemplate redis;

    /** 注入锁实现和模板；模板只用于删除脏 key 与采样 TTL。 */
    public SimpleLockService(SimpleRedisLock lock, StringRedisTemplate redis) {
        this.lock = lock;
        this.redis = redis;
    }

    /**
     * A 先拿锁并执行 holdMillis；B 在 ttlMillis + 300ms 时来抢。
     *
     * @param name       key 后缀，同一个 name 的实验共享 key
     * @param ttlMillis  锁 TTL（毫秒）；越短越容易观察到过期
     * @param holdMillis A 模拟业务执行时间（毫秒）
     * @param watchdog   true 时 A 每 ttl/3 续期一次，B 抢不到；false 时锁到期，B 能进来
     * @return 事件时间线、TTL 采样、是否重叠、是否续期
     */
    public SimpleLockResult demo(String name, long ttlMillis, long holdMillis, boolean watchdog) {
        String key = KEY_PREFIX + name;
        // 先清理，保证重复调用从同一初始状态开始；否则上一轮残留的锁会干扰结果
        redis.delete(key);

        // 两个线程都会写事件和采样，必须用线程安全集合，否则可能丢记录
        List<Event> events = Collections.synchronizedList(new ArrayList<>());
        List<TtlSample> samples = Collections.synchronizedList(new ArrayList<>());
        long start = System.currentTimeMillis();
        // 持有者标识必须唯一：它决定解锁/续期时能否通过 Lua 校验
        String holderA = "A-" + UUID.randomUUID();
        String holderB = "B-" + UUID.randomUUID();
        // aHolding 表示 A 是否仍认为自己处在临界区，用来判断是否发生重叠
        AtomicBoolean aHolding = new AtomicBoolean(false);
        AtomicBoolean overlap = new AtomicBoolean(false);
        AtomicBoolean renewed = new AtomicBoolean(false);
        // aAcquired 让主线程等 A 完成加锁；aFinished 让主线程/B 等待 A 结束
        CountDownLatch aAcquired = new CountDownLatch(1);
        CountDownLatch aFinished = new CountDownLatch(1);

        // 两个定时任务：TTL 采样和看门狗续期，互不阻塞，因此用 2 个线程
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
        Thread threadA = new Thread(() -> {
            boolean acquired = lock.tryLock(key, holderA, ttlMillis);
            events.add(new Event(elapsed(start), "A", "tryLock", acquired,
                    "TTL=" + ttlMillis + "ms"));
            aAcquired.countDown();
            if (!acquired) {
                // 没抢到锁就没必要执行临界区，直接结束，避免主线程一直等
                aFinished.countDown();
                return;
            }
            aHolding.set(true);
            // 模拟业务耗时：holdMillis > ttlMillis 是观察锁过期的关键
            sleep(holdMillis);
            boolean unlocked = lock.unlock(key, holderA);
            aHolding.set(false);
            events.add(new Event(elapsed(start), "A", "unlock", unlocked,
                    unlocked ? "释放成功" : "锁已不属于自己，Lua 校验失败"));
            aFinished.countDown();
        }, "lock-a");
        threadA.start();

        // 等 A 真正拿到锁再开始采样，否则会采到不存在的 key（TTL=-2）
        await(aAcquired, 2000);

        // 每 200ms 采样一次 TTL：小于最短 TTL(500ms)，能看清递减与续期跳变
        ScheduledFuture<?> sampler = scheduler.scheduleAtFixedRate(() -> {
            if (!aHolding.get()) {
                return;
            }
            Long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
            samples.add(new TtlSample(elapsed(start), ttl == null ? -2 : ttl));
        }, 100, 200, TimeUnit.MILLISECONDS);

        if (watchdog) {
            // 续期间隔取 TTL/3：留出两次重试机会，网络抖动时也不至于在下次续期前过期
            long interval = Math.max(ttlMillis / 3, 100);
            scheduler.scheduleAtFixedRate(() -> {
                if (!aHolding.get()) {
                    return;
                }
                boolean ok = lock.renew(key, holderA, ttlMillis);
                if (ok) {
                    renewed.set(true);
                    events.add(new Event(elapsed(start), "A", "watchdog renew", true,
                            "每 " + interval + "ms 续期到 " + ttlMillis + "ms"));
                }
                // 续期失败通常意味着锁已过期/被抢，真实实现应在这里中断业务；
                // 本实验为了让 A 继续跑到 holdMillis，只记录成功事件
            }, interval, interval, TimeUnit.MILLISECONDS);
        }

        // B 在锁的原始 TTL 过期之后来抢：不续期时必能拿到，续期时会被挡住
        sleep(ttlMillis + 300);
        boolean bAcquired = lock.tryLock(key, holderB, ttlMillis);
        if (bAcquired) {
            if (aHolding.get()) {
                // A 仍认为自己在临界区，但 B 已拿到锁：锁的保护已经失效
                overlap.set(true);
            }
            events.add(new Event(elapsed(start), "B", "tryLock", true,
                    aHolding.get() ? "A 还在执行，B 却拿到了锁：临界区重叠" : "A 已结束，正常获取"));
            // 停留 200ms，让重叠窗口在采样和时间线上可见，而不是转瞬即逝
            sleep(200);
            lock.unlock(key, holderB);
            events.add(new Event(elapsed(start), "B", "unlock", true, "B 释放"));
        } else {
            events.add(new Event(elapsed(start), "B", "tryLock", false,
                    watchdog ? "A 的锁被续期，B 抢不到" : "A 仍持有锁"));
            if (watchdog) {
                // 看门狗生效时 B 这次抢不到；等 A 正常释放后再验证锁确实可被获取
                await(aFinished, holdMillis + 2000);
                boolean retried = lock.tryLock(key, holderB, ttlMillis);
                events.add(new Event(elapsed(start), "B", "retryLock", retried,
                        retried ? "A 释放后 B 获取成功" : "仍未获取"));
                if (retried) {
                    lock.unlock(key, holderB);
                }
            }
        }

        // 等 A 收尾，再关闭调度器；顺序反了可能让采样线程访问已关闭的执行器
        await(aFinished, holdMillis + 3000);
        sampler.cancel(false);
        scheduler.shutdownNow();
        // 清理实验 key，避免影响下一次调用
        redis.delete(key);

        // 两个线程并发写入，事件顺序不保证；按时间排序后再返回，方便前端画时间线
        events.sort(Comparator.comparingLong(Event::atMillis));
        // scenario 字段直接标明是否启用看门狗，调用方无需再推断
        return new SimpleLockResult(watchdog ? "simple-watchdog" : "simple-no-watchdog",
                key, ttlMillis, holdMillis, overlap.get(), renewed.get(),
                List.copyOf(events), List.copyOf(samples));
    }

    /** 相对方法开始的毫秒数，保证事件时间线可比较。 */
    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    /** 演示用 sleep：被中断时恢复中断标记，不吞掉异常。 */
    private static void sleep(long millis) {
        try {
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 带超时的等待，避免实验因线程异常而永久挂起；超时后继续走后续清理。 */
    private static void await(CountDownLatch latch, long timeoutMillis) {
        try {
            latch.await(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
