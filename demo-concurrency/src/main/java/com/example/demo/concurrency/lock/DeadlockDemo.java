package com.example.demo.concurrency.lock;

import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Service;

import com.example.demo.concurrency.support.DemoThreads;
import com.example.demo.concurrency.support.Observations.TimelineEvent;
import com.example.demo.concurrency.support.ThreadSampler;

/**
 * 场景 5：AB-BA 死锁的确定性复现、{@code ThreadMXBean} 检测与固定顺序修复。
 *
 * <p>非修复版用两个 latch 卡住时序：T1 先拿 A、T2 先拿 B，双方确认对方已持有第一把锁后，
 * 再分别去拿对方的锁，必然形成环：</p>
 * <pre>
 * T1: A ──等待──▶ B
 * T2: B ──等待──▶ A
 * </pre>
 *
 * <p>修复版让两个线程都按 A→B 的顺序加锁，环被打破，T2 在 A 上排队后完成。</p>
 *
 * <p>关键细节：第二把锁用 {@code lockInterruptibly()} 而不是 {@code lock()}。
 * 死锁检测到之后可以对线程 {@code interrupt()}，让它从 park 中醒来、抛异常并在
 * finally 里释放第一把锁，实验进程才能恢复；如果用的是 {@code lock()}，
 * 两个线程只能重启 JVM 才能解开——这也是生产环境死锁往往要重启的原因。</p>
 */
@Service
public class DeadlockDemo {

    /** 请求级编号，避免多次调用/并发请求的线程名冲突。 */
    private static final AtomicLong RUN_ID = new AtomicLong();

    /**
     * 跑一次死锁实验。
     *
     * @param fixed              true 使用固定加锁顺序 A→B；false 使用 A→B / B→A 的交叉顺序
     * @param holdMillis         拿到第二把锁后模拟业务的持锁时间（毫秒）
     * @param detectTimeoutMillis 非修复版轮询死锁的最长时间（毫秒）
     * @return 是否检测到死锁、死锁线程详情、清理是否成功与时间线
     */
    public Result run(boolean fixed, long holdMillis, long detectTimeoutMillis) {
        long runId = RUN_ID.incrementAndGet();
        ReentrantLock lockA = new ReentrantLock();
        ReentrantLock lockB = new ReentrantLock();
        List<TimelineEvent> timeline = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        long startNanos = System.nanoTime();

        Thread t1;
        Thread t2;
        if (fixed) {
            // 两个线程都按 A→B 加锁：不会出现“各持一把、互等对方”的环
            t1 = DemoThreads.start("dead-" + runId + "-T1", () ->
                    fixedWorker("T1", lockA, lockB, holdMillis, startGate, done, timeline, startNanos));
            t2 = DemoThreads.start("dead-" + runId + "-T2", () ->
                    fixedWorker("T2", lockA, lockB, holdMillis, startGate, done, timeline, startNanos));
        } else {
            CountDownLatch aHeld = new CountDownLatch(1);
            CountDownLatch bHeld = new CountDownLatch(1);
            // 交叉顺序：T1 先 A 后 B，T2 先 B 后 A
            t1 = DemoThreads.start("dead-" + runId + "-T1", () ->
                    crossWorker("T1", lockA, lockB, aHeld, bHeld, holdMillis,
                            startGate, done, timeline, interrupted, startNanos));
            t2 = DemoThreads.start("dead-" + runId + "-T2", () ->
                    crossWorker("T2", lockB, lockA, bHeld, aHeld, holdMillis,
                            startGate, done, timeline, interrupted, startNanos));
        }

        startGate.countDown();
        Set<Long> workerIds = Set.of(t1.threadId(), t2.threadId());
        long[] deadlockedIds = new long[0];

        if (!fixed) {
            // 轮询而不是 sleep 固定时长：死锁通常在几十毫秒内形成，检出后立即记录
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(detectTimeoutMillis);
            while (System.nanoTime() < deadline) {
                deadlockedIds = ThreadSampler.deadlockedAmong(workerIds);
                if (deadlockedIds.length >= 2) {
                    break;
                }
                sleepQuietly(20);
            }
        }

        List<DeadlockThread> deadlockedThreads = new ArrayList<>();
        if (deadlockedIds.length >= 2) {
            // 先抓取 ThreadInfo（含锁对象、持有者和栈），再 interrupt；否则线程退出后信息就没了
            for (ThreadInfo info : ThreadSampler.details(deadlockedIds)) {
                if (info != null) {
                    deadlockedThreads.add(toRecord(info));
                }
            }
        }

        if (!fixed) {
            // 修复版不需要中断；交叉版无论是否检出，都中断一次以回收可能仍阻塞的线程
            t1.interrupt();
            t2.interrupt();
        }

        await(done, fixed ? holdMillis * 4 + 3000 : 5000);
        DemoThreads.join(t1, 2000);
        DemoThreads.join(t2, 2000);
        // 如果仍有线程活着（例如 lock() 不可中断），再补一次中断，避免请求结束后泄漏线程
        if (t1.isAlive()) {
            t1.interrupt();
            DemoThreads.join(t1, 1000);
        }
        if (t2.isAlive()) {
            t2.interrupt();
            DemoThreads.join(t2, 1000);
        }

        boolean detected = deadlockedIds.length >= 2;
        boolean allFinished = !t1.isAlive() && !t2.isAlive();
        boolean noDeadlockLeft = ThreadSampler.deadlockedAmong(workerIds).length == 0;
        boolean cleanedUp = allFinished && noDeadlockLeft;
        timeline.sort(Comparator.comparingLong(TimelineEvent::atMillis));

        String conclusion;
        if (fixed) {
            conclusion = allFinished
                    ? "固定加锁顺序（A→B）后没有死锁，两个线程在 "
                    + elapsedMillis(startNanos) + "ms 内完成"
                    : "修复版未在预期时间内完成，请检查 holdMillis 或线程调度";
        } else if (detected) {
            conclusion = "AB-BA 死锁：两个线程各持一把锁并等待对方；"
                    + "ThreadMXBean 检出 2 个死锁线程，interrupt 后恢复";
        } else {
            conclusion = "本轮未检测到死锁，可重跑；死锁是时序问题，固定编排下通常能稳定复现";
        }

        return new Result(fixed, detected, cleanedUp, allFinished, interrupted.get(),
                elapsedMillis(startNanos), List.copyOf(deadlockedThreads),
                List.copyOf(timeline), conclusion);
    }

    /** 固定顺序：先 first 再 second；两个线程使用同一顺序，因此只会排队不会成环。 */
    private void fixedWorker(String role, ReentrantLock first, ReentrantLock second,
                             long holdMillis, CountDownLatch startGate, CountDownLatch done,
                             List<TimelineEvent> timeline, long startNanos) {
        try {
            startGate.await();
            timeline.add(new TimelineEvent(elapsedMillis(startNanos), role, "acquire-first", ""));
            first.lock();
            try {
                timeline.add(new TimelineEvent(elapsedMillis(startNanos), role, "first-held", ""));
                // 短暂持有，让另一个线程也来抢 first，证明“同顺序=排队”
                sleepQuietly(Math.min(50, holdMillis));
                second.lock();
                try {
                    timeline.add(new TimelineEvent(elapsedMillis(startNanos), role, "second-held", ""));
                    sleepQuietly(holdMillis);
                } finally {
                    second.unlock();
                }
            } finally {
                first.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            done.countDown();
        }
    }

    /** 交叉顺序：先拿 own，确认对方也拿到它自己的第一把锁后，再去拿 other。 */
    private void crossWorker(String role, ReentrantLock first, ReentrantLock second,
                             CountDownLatch ownHeld, CountDownLatch otherHeld,
                             long holdMillis, CountDownLatch startGate, CountDownLatch done,
                             List<TimelineEvent> timeline, AtomicBoolean interrupted,
                             long startNanos) {
        try {
            startGate.await();
            first.lock();
            try {
                ownHeld.countDown();
                timeline.add(new TimelineEvent(elapsedMillis(startNanos), role, "first-held", ""));
                // 等对方也拿到第一把锁：双方都持有一把，才有条件形成环
                if (!otherHeld.await(2, TimeUnit.SECONDS)) {
                    timeline.add(new TimelineEvent(elapsedMillis(startNanos), role,
                            "abort", "对方未在 2s 内持有第一把锁"));
                    return;
                }
                timeline.add(new TimelineEvent(elapsedMillis(startNanos), role, "second-attempt", ""));
                try {
                    // 可中断是实验能恢复的前提；生产代码也应优先选择带超时/可中断的锁
                    second.lockInterruptibly();
                    try {
                        timeline.add(new TimelineEvent(elapsedMillis(startNanos), role, "second-held", ""));
                        sleepQuietly(holdMillis);
                    } finally {
                        second.unlock();
                    }
                } catch (InterruptedException e) {
                    interrupted.set(true);
                    timeline.add(new TimelineEvent(elapsedMillis(startNanos), role,
                            "interrupted", "从等待第二把锁中醒来"));
                }
            } finally {
                first.unlock();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            done.countDown();
        }
    }

    /** 把 JMX 的 ThreadInfo 转成响应记录，只保留前 4 层栈避免返回体过大。 */
    private static DeadlockThread toRecord(ThreadInfo info) {
        List<String> topFrames = Arrays.stream(info.getStackTrace())
                .limit(4)
                .map(StackTraceElement::toString)
                .toList();
        return new DeadlockThread(info.getThreadName(), info.getThreadId(),
                info.getThreadState().name(), info.getLockName(), info.getLockOwnerName(),
                topFrames);
    }

    /** 纳秒起点换算成相对毫秒。 */
    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** 等待 latch；超时或中断都继续执行。 */
    private static void await(CountDownLatch latch, long millis) {
        try {
            latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 睡眠；被中断时恢复标记。 */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 一个死锁线程的 JMX 快照。
     *
     * @param threadName    线程名
     * @param threadId      线程 id
     * @param state         BLOCKED / WAITING 等
     * @param waitingOn     正在等待的锁对象
     * @param lockOwner     锁的当前持有者线程名
     * @param topFrames     栈顶 4 帧，能直接看到卡在 lockInterruptibly
     */
    public record DeadlockThread(String threadName, long threadId, String state,
                                 String waitingOn, String lockOwner,
                                 List<String> topFrames) {
    }

    /**
     * 死锁实验结果。
     *
     * @param fixed            是否走了固定顺序修复版
     * @param deadlockDetected 是否检出 2 个本实验线程的死锁
     * @param cleanedUp        线程是否全部结束且 JVM 不再报告这些线程死锁
     * @param allThreadsFinished 两个 worker 是否都已终止
     * @param interrupted      是否有线程因 interrupt 从等待中醒来
     * @param elapsedMillis    实验总耗时
     * @param deadlockedThreads 死锁线程详情；未检出时为空
     * @param timeline         时间线
     * @param conclusion       一句话结论
     */
    public record Result(boolean fixed, boolean deadlockDetected, boolean cleanedUp,
                         boolean allThreadsFinished, boolean interrupted,
                         long elapsedMillis, List<DeadlockThread> deadlockedThreads,
                         List<TimelineEvent> timeline, String conclusion) {
    }
}
