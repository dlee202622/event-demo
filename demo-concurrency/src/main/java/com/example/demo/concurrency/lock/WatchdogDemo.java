package com.example.demo.concurrency.lock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

import com.example.demo.concurrency.support.DemoThreads;
import com.example.demo.concurrency.support.Observations.TimelineEvent;

/**
 * 进阶场景 1：租约锁的看门狗（watchdog）机制。
 *
 * <p>这里模拟的是 Redisson 分布式锁的核心语义：锁不是“永久持有”，而是带一个
 * <strong>租约（lease）</strong>——持有者必须在到期前续期，否则其他线程可以接管。
 * 本类不依赖 Redis，用内存中的过期时间戳复现同样的时序，方便单独观察机制。</p>
 *
 * <p>实验对照：</p>
 * <ul>
 *   <li>{@code watchdog=false}：A 拿到 lease=800ms 的锁后睡 2500ms，租约到期无人续期，
 *       B 在 1000ms 左右闯入；A 结束时锁已不属于它，释放操作必须被忽略。</li>
 *   <li>{@code watchdog=true}：守护线程每 lease/3 续期一次，B 一直抢不到，
 *       直到 A 正常释放；A 释放后看门狗立即停止，不能出现“幽灵续期”。</li>
 * </ul>
 *
 * <p>关键坑：续期必须在“仍持有且尚未过期”时进行。如果续期线程被 GC 或调度延迟到
 * 租约过期之后才醒来，再续期就等于把已经被别人接管的锁又“复活”了；本实现里
 * {@link Lease#renew} 会拒绝这种情况。</p>
 */
@Service
public class WatchdogDemo {

    /** 每次实验的编号：线程名带上它，多次请求的线程不会在快照里混淆。 */
    private static final AtomicLong RUN_ID = new AtomicLong();

    /** B 在租约到期后再等多久开始抢锁，给“无看门狗”场景留出确定性的过期窗口。 */
    private static final long CONTEND_DELAY_AFTER_LEASE_MS = 200;

    /** B 抢锁失败后的重试间隔；太小会空耗 CPU，太大会把获取时间拖得过晚。 */
    private static final long RETRY_INTERVAL_MS = 25;

    /**
     * 执行一次租约锁对照实验。
     *
     * @param leaseMillis 租约时长（毫秒），范围由控制器校验
     * @param holdMillis  A 模拟的业务执行时间（毫秒），必须覆盖租约到期点才能看到差异
     * @param watchdog    true 时启动续期线程；false 时让租约自然过期
     * @return 两个线程的获取/释放时间、重叠标志、续期次数和完整时间线
     */
    public WatchdogResult run(long leaseMillis, long holdMillis, boolean watchdog) {
        long runId = RUN_ID.incrementAndGet();
        long startNanos = System.nanoTime();
        // 多个线程都会追加事件，用同步包装保证时间线不会在扩容时丢事件
        List<TimelineEvent> timeline = Collections.synchronizedList(new ArrayList<>());

        Lease lease = new Lease();
        AtomicLong renewCount = new AtomicLong();
        AtomicLong renewCountAtRelease = new AtomicLong();
        AtomicBoolean staleRelease = new AtomicBoolean(false);
        AtomicLong aReleasedAt = new AtomicLong(-1);
        AtomicLong bAcquiredAt = new AtomicLong(-1);
        CountDownLatch bDone = new CountDownLatch(1);

        long contendAtMillis = leaseMillis + CONTEND_DELAY_AFTER_LEASE_MS;
        long renewIntervalMillis = Math.max(1, leaseMillis / 3);
        // B 在 A 释放之后还要持有一小段时间，用来验证 A 的迟到 release 不会误删 B 的锁。
        // 无看门狗时 B 早已拿到锁，必须让它活过 A 的释放点；有看门狗时 B 本来就晚到，
        // 只需短暂持有即可，避免把一次请求拖到 4 秒以上。
        long bHoldMillis = watchdog ? 120 : Math.max(200, holdMillis - contendAtMillis + 200);

        ScheduledExecutorService renewer = watchdog
                ? Executors.newSingleThreadScheduledExecutor(daemonFactory("wd-renewer-" + runId))
                : null;

        Thread a = DemoThreads.start("wd-A-" + runId, () -> {
            if (!lease.tryAcquire("A", leaseMillis)) {
                addEvent(timeline, startNanos, "A", "acquire-failed", "租约仍被占用");
                return;
            }
            addEvent(timeline, startNanos, "A", "acquire", "lease=" + leaseMillis + "ms");
            sleepQuietly(holdMillis);

            // 释放前先停掉看门狗：真实实现必须在 unlock 时取消续期任务，
            // 否则任务可能在锁已经属于别人之后继续把 TTL 拉长。
            if (renewer != null) {
                renewer.shutdownNow();
                awaitTermination(renewer);
            }
            renewCountAtRelease.set(renewCount.get());

            boolean released = lease.release("A");
            aReleasedAt.set(elapsedMillis(startNanos));
            if (!released) {
                // 走到这里说明 A 的租约已经过期、锁被 B 接管；这次 release 必须无效
                staleRelease.set(true);
            }
            addEvent(timeline, startNanos, "A", released ? "release" : "release-ignored",
                    released ? "锁已释放" : "租约已过期，当前 owner 不是 A");
        });

        Thread b = DemoThreads.start("wd-B-" + runId, () -> {
            sleepUntil(startNanos, contendAtMillis);
            addEvent(timeline, startNanos, "B", "contend", "开始尝试获取锁");
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(holdMillis + leaseMillis + 2_000);
            try {
                while (System.nanoTime() < deadline) {
                    if (lease.tryAcquire("B", leaseMillis)) {
                        bAcquiredAt.set(elapsedMillis(startNanos));
                        addEvent(timeline, startNanos, "B", "acquire",
                                "A 是否仍在执行=" + (aReleasedAt.get() < 0));
                        sleepQuietly(bHoldMillis);
                        lease.release("B");
                        addEvent(timeline, startNanos, "B", "release", "B 正常释放");
                        return;
                    }
                    sleepQuietly(RETRY_INTERVAL_MS);
                }
                addEvent(timeline, startNanos, "B", "give-up", "超过等待上限仍未拿到锁");
            } finally {
                bDone.countDown();
            }
        });

        if (renewer != null) {
            renewer.scheduleAtFixedRate(() -> {
                if (lease.renew("A", leaseMillis)) {
                    renewCount.incrementAndGet();
                    addEvent(timeline, startNanos, "renewer", "renew",
                            "续期到 " + leaseMillis + "ms，累计=" + renewCount.get());
                } else {
                    // owner 已变或租约已过期：续期成功反而会把别人的锁续上，必须停止
                    addEvent(timeline, startNanos, "renewer", "renew-skipped",
                            "owner 不是 A 或租约已过期");
                    renewer.shutdown();
                }
            }, renewIntervalMillis, renewIntervalMillis, TimeUnit.MILLISECONDS);
        }

        DemoThreads.join(a, holdMillis + 3_000);
        DemoThreads.join(b, holdMillis + leaseMillis + 3_000);
        try {
            bDone.await(holdMillis + leaseMillis + 3_000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (renewer != null) {
            // 正常情况下 A 已经关闭了它；这里兜底，保证实验线程不会泄漏到下一次请求
            renewer.shutdownNow();
        }

        long acquired = bAcquiredAt.get();
        long released = aReleasedAt.get();
        boolean overlap = acquired >= 0 && released >= 0 && acquired < released;
        boolean renewedAfterRelease = renewCount.get() > renewCountAtRelease.get();
        String conclusion = conclusion(watchdog, overlap, leaseMillis, holdMillis,
                acquired, released, renewCount.get(), renewIntervalMillis);

        return new WatchdogResult(watchdog, leaseMillis, holdMillis, contendAtMillis,
                overlap, renewCount.get(), renewedAfterRelease, staleRelease.get(),
                released, acquired, lease.owner(), List.copyOf(timeline), conclusion,
                "续期间隔取 lease/3，与 Redisson 默认 TTL/3 一致；B 每 "
                        + RETRY_INTERVAL_MS + "ms 重试一次；时间均为相对实验开始的毫秒数");
    }

    /** 根据实验模式生成一句可直接写进 README 的结论。 */
    private static String conclusion(boolean watchdog, boolean overlap, long leaseMillis,
                                     long holdMillis, long acquired, long released,
                                     long renewCount, long renewIntervalMillis) {
        if (watchdog) {
            return "看门狗每 " + renewIntervalMillis + "ms 续期一次，共续期 " + renewCount
                    + " 次；B 到 A 释放（" + released + "ms）后才进入，overlap=" + overlap;
        }
        if (overlap) {
            return "租约 " + leaseMillis + "ms 到期后无人续期，B 在 " + acquired
                    + "ms 闯入，而 A 直到 " + released + "ms 才结束，overlap=true";
        }
        return "租约在 A 结束前没有到期（hold=" + holdMillis + "ms），B 只能等 A 释放后进入；"
                + "把 leaseMillis 调小才能看到过期接管";
    }

    private static ThreadFactory daemonFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            // 守护线程：即使测试或请求提前结束，也不会阻止 JVM 退出
            thread.setDaemon(true);
            return thread;
        };
    }

    /** 追加一条时间线事件；所有时间都换算成相对本次实验开始的毫秒数。 */
    private static void addEvent(List<TimelineEvent> timeline, long startNanos,
                                 String thread, String action, String note) {
        timeline.add(new TimelineEvent(elapsedMillis(startNanos), thread, action, note));
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** 睡到相对开始时刻的指定毫秒；已经过了就立即返回。 */
    private static void sleepUntil(long startNanos, long targetMillis) {
        long remaining = targetMillis - elapsedMillis(startNanos);
        if (remaining > 0) {
            sleepQuietly(remaining);
        }
    }

    /** 演示线程的 sleep 不需要向上抛中断；恢复标记后继续，避免吞掉取消信号。 */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitTermination(ScheduledExecutorService executor) {
        try {
            executor.awaitTermination(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 最小租约锁：owner + 过期时间就是全部状态。
     *
     * <p>这里用 synchronized 保护状态而不是 CAS，原因是本实验要观察“过期后接管”的语义，
     * 把状态判断和写入放在同一个临界区里最直观；真实分布式锁则把这段判断放进 Redis
     * 的 Lua 脚本，保证“判断 + 设置”在服务端原子执行。</p>
     */
    private static final class Lease {

        private String owner;
        private long expireAtNanos;

        /** 只有当前没有 owner，或旧租约已经过期时才能获取；过期判断与写入必须原子。 */
        synchronized boolean tryAcquire(String candidate, long leaseMillis) {
            long now = System.nanoTime();
            if (owner == null || now >= expireAtNanos) {
                owner = candidate;
                expireAtNanos = now + TimeUnit.MILLISECONDS.toNanos(leaseMillis);
                return true;
            }
            return false;
        }

        /**
         * 仅当 owner 仍是 expected 且租约尚未过期时续期。
         *
         * <p>拒绝“过期后复活”是看门狗最容易写错的地方：如果续期线程延迟醒来，
         * 此时锁可能已经被别人拿走，再写过期时间就会破坏互斥。</p>
         */
        synchronized boolean renew(String expected, long leaseMillis) {
            long now = System.nanoTime();
            if (!expected.equals(owner) || now >= expireAtNanos) {
                return false;
            }
            expireAtNanos = now + TimeUnit.MILLISECONDS.toNanos(leaseMillis);
            return true;
        }

        /** 带 owner 校验的释放：迟到的旧持有者不能释放新持有者的锁。 */
        synchronized boolean release(String expected) {
            if (expected.equals(owner)) {
                owner = null;
                return true;
            }
            return false;
        }

        /** 当前有效 owner；租约已过期时视为无人持有。 */
        synchronized String owner() {
            return owner != null && System.nanoTime() >= expireAtNanos ? null : owner;
        }
    }

    /**
     * 看门狗实验结果。
     *
     * @param watchdog             是否开启续期
     * @param leaseMillis          租约时长
     * @param holdMillis           A 的业务执行时间
     * @param contendAtMillis      B 开始抢锁的相对时间
     * @param overlap              B 是否在 A 释放前拿到锁（互斥被破坏）
     * @param renewCount           看门狗成功续期次数
     * @param renewedAfterRelease  释放后是否还发生续期（应为 false）
     * @param staleReleaseIgnored  A 的迟到 release 是否被忽略（无看门狗时应为 true）
     * @param aReleasedAtMillis    A 调用 release 的相对时间
     * @param bAcquiredAtMillis    B 拿到锁的相对时间
     * @param ownerAtEnd           实验结束时仍有效的 owner；正常应为 null
     * @param timeline             完整事件序列
     * @param conclusion           本次实验结论
     * @param note                 统计口径
     */
    public record WatchdogResult(boolean watchdog, long leaseMillis, long holdMillis,
                                 long contendAtMillis, boolean overlap, long renewCount,
                                 boolean renewedAfterRelease, boolean staleReleaseIgnored,
                                 long aReleasedAtMillis, long bAcquiredAtMillis, String ownerAtEnd,
                                 List<TimelineEvent> timeline, String conclusion, String note) {
    }
}
