package com.example.demo.concurrency.lock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Service;

import com.example.demo.concurrency.support.DemoThreads;
import com.example.demo.concurrency.support.Observations.TimelineEvent;

/**
 * 场景 2：{@link ReentrantLock} 的可重入、AQS 等待队列与“解锁次数必须匹配”。
 *
 * <p>持有者线程连续 {@code lock()} 三次，再在队列里排 N 个等待者：</p>
 * <ul>
 *   <li>{@code getHoldCount()} 依次是 1 / 2 / 3，证明同一线程可以重复获取；</li>
 *   <li>等待者全部入队后 {@code getQueueLength()} 等于等待者数量，这是 AQS 队列的可观测形态；</li>
 *   <li>持有者释放三次后队列才放行；最后由请求线程故意多 unlock 一次，
 *       触发 {@link IllegalMonitorStateException}，说明“只有持有者能释放”。</li>
 * </ul>
 *
 * <p>坑：{@code lock()} 与 {@code unlock()} 必须成对。少一次 unlock 会让锁永远不释放；
 * 多一次 unlock 会立刻抛异常。生产代码通常把 unlock 放在 finally 里，并用
 * {@code tryLock(timeout)} 给等待设置上限。</p>
 */
@Service
public class ReentrantLockDemo {

    /** 请求级编号，保证线程名在多次调用之间不重复。 */
    private static final AtomicLong RUN_ID = new AtomicLong();
    /** 持有者线程名后缀；结果里用这个名字对照 AQS 的 owner。 */
    private static final String HOLDER = "holder";

    /**
     * 跑一次“三层重入 + N 个等待者排队”的实验。
     *
     * @param waiters    等待者线程数，至少 1；它们会同时卡在 lock() 上
     * @param holdMillis 队列排满后持有者继续持锁的时间（毫秒），用于稳定采样队列长度
     * @return 每次 lock/unlock 后的 holdCount、队列采样、获取顺序与误用异常
     */
    public Result run(int waiters, long holdMillis) {
        long runId = RUN_ID.incrementAndGet();
        ReentrantLock lock = new ReentrantLock();
        CountDownLatch holderAcquired = new CountDownLatch(1);
        CountDownLatch queueReady = new CountDownLatch(1);
        CountDownLatch attempts = new CountDownLatch(waiters);
        CountDownLatch done = new CountDownLatch(waiters + 1);
        List<LockStep> lockSteps = Collections.synchronizedList(new ArrayList<>());
        List<TimelineEvent> timeline = Collections.synchronizedList(new ArrayList<>());
        List<String> acquisitionOrder = Collections.synchronizedList(new ArrayList<>());
        List<Integer> queueLengthSamples = Collections.synchronizedList(new ArrayList<>());
        long startNanos = System.nanoTime();
        List<Thread> workers = new ArrayList<>(waiters + 1);

        Thread holder = DemoThreads.start("rlock-" + runId + "-" + HOLDER, () -> {
            lock.lock();
            try {
                // 同一线程重复加锁：holdCount 是“当前线程重入了几层”，不是全局计数
                lockSteps.add(new LockStep("lock", 1, lock.getHoldCount()));
                lock.lock();
                lockSteps.add(new LockStep("lock", 2, lock.getHoldCount()));
                lock.lock();
                lockSteps.add(new LockStep("lock", 3, lock.getHoldCount()));
                timeline.add(new TimelineEvent(elapsedMillis(startNanos), HOLDER, "hold",
                        "holdCount=" + lock.getHoldCount()));
                holderAcquired.countDown();

                try {
                    // 等主线程确认“所有等待者都已入队”，再继续持有一段时间供采样
                    queueReady.await(3, TimeUnit.SECONDS);
                    Thread.sleep(holdMillis);
                } catch (InterruptedException e) {
                    // 实验不主动中断持有者；恢复标记后仍然走 finally 释放锁
                    Thread.currentThread().interrupt();
                }
            } finally {
                // 三次加锁对应三次解锁，每次记录释放后的 holdCount：2 → 1 → 0
                lock.unlock();
                lockSteps.add(new LockStep("unlock", 3, lock.getHoldCount()));
                lock.unlock();
                lockSteps.add(new LockStep("unlock", 2, lock.getHoldCount()));
                lock.unlock();
                lockSteps.add(new LockStep("unlock", 1, lock.getHoldCount()));
                timeline.add(new TimelineEvent(elapsedMillis(startNanos), HOLDER, "release",
                        "holdCount=" + lock.getHoldCount()));
                done.countDown();
            }
        });
        workers.add(holder);

        await(holderAcquired, 2000);

        // 依次启动等待者；每个线程先 countDown 表示“马上要 lock”，再阻塞在 lock() 上
        for (int i = 1; i <= waiters; i++) {
            String name = "waiter-" + i;
            Thread waiter = DemoThreads.start("rlock-" + runId + "-w" + i, () -> {
                attempts.countDown();
                try {
                    lock.lock();
                    try {
                        // 能执行到这里，说明已从 AQS 队列中被唤醒并拿到锁
                        acquisitionOrder.add(name);
                        timeline.add(new TimelineEvent(elapsedMillis(startNanos), name, "acquired",
                                "queueLength=" + lock.getQueueLength()));
                    } finally {
                        lock.unlock();
                    }
                } finally {
                    done.countDown();
                }
            });
            workers.add(waiter);
        }

        await(attempts, 2000);

        // attempts 只代表“准备 lock”，线程可能还没真正入队；轮询到队列长度达到 waiters
        long queueDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (lock.getQueueLength() < waiters && System.nanoTime() < queueDeadline) {
            sleepQuietly(10);
        }

        int maxQueueLength = 0;
        // 持有者还在 sleep，队列不会散去；连续采样几次，确认长度稳定而不是瞬时值
        for (int i = 0; i < 5; i++) {
            int queueLength = lock.getQueueLength();
            queueLengthSamples.add(queueLength);
            maxQueueLength = Math.max(maxQueueLength, queueLength);
            sleepQuietly(20);
        }

        queueReady.countDown();
        await(done, holdMillis + 5000);
        for (Thread worker : workers) {
            DemoThreads.join(worker, 2000);
        }

        // 所有线程都已释放锁，此时主线程并不持有锁；故意 unlock 观察 AQS 的持有者校验
        Misuse misuse;
        try {
            lock.unlock();
            misuse = new Misuse("unlock-without-holding", "NONE",
                    "意外成功：未持有锁却解锁成功，说明实现有问题");
        } catch (IllegalMonitorStateException e) {
            misuse = new Misuse("unlock-without-holding", e.getClass().getSimpleName(),
                    e.getMessage() == null ? "(no message)" : e.getMessage());
        }

        boolean allWaitersAcquired = acquisitionOrder.size() == waiters;
        boolean lockFreeAtEnd = !lock.isLocked() && !lock.hasQueuedThreads();
        String conclusion;
        if (allWaitersAcquired && lockFreeAtEnd) {
            conclusion = "可重入 holdCount 依次为 1/2/3；" + waiters
                    + " 个等待者全部获取；多余 unlock 抛出 IllegalMonitorStateException";
        } else {
            conclusion = "等待者未全部完成或锁未完全释放（acquired=" + acquisitionOrder.size()
                    + "/" + waiters + "，locked=" + lock.isLocked() + "）";
        }

        return new Result(waiters, holdMillis, lockSteps, timeline,
                maxQueueLength, queueLengthSamples, acquisitionOrder,
                allWaitersAcquired, elapsedMillis(startNanos), misuse,
                lockFreeAtEnd, conclusion);
    }

    /** 把纳秒起点换算成相对毫秒。 */
    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** 等待 latch；超时/中断都继续，保证接口最终返回可诊断信息。 */
    private static void await(CountDownLatch latch, long millis) {
        try {
            latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 轮询间隔用的 sleep；被中断时恢复标记，不吞掉中断。 */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 一次 lock / unlock 之后的锁状态快照。
     *
     * @param action    lock 或 unlock
     * @param depth     这次操作对应第几层嵌套（lock 时 1→3，unlock 时 3→1）
     * @param holdCount 操作完成后当前线程的 holdCount
     */
    public record LockStep(String action, int depth, int holdCount) {
    }

    /**
     * 故意的误用结果，用来证明 unlock 会校验持有者。
     *
     * @param caseName  误用场景名
     * @param exception 抛出的异常类型；如果意外成功则为 NONE
     * @param message   异常消息（JDK 可能不给消息）
     */
    public record Misuse(String caseName, String exception, String message) {
    }

    /**
     * 实验结果。
     *
     * @param waiters           等待者数量
     * @param holdMillis        队列排满后持有者继续持锁的时间
     * @param lockSteps         每次 lock/unlock 后的 holdCount，按执行顺序
     * @param timeline          带时间点的事件线
     * @param maxQueueLength    采样到的最大 AQS 队列长度
     * @param queueLengthSamples 连续采样的队列长度
     * @param acquisitionOrder  等待者实际拿到锁的顺序
     * @param allWaitersAcquired 是否所有等待者都拿到了锁
     * @param elapsedMillis     实验总耗时
     * @param misuse            故意多 unlock 一次的异常记录
     * @param lockFreeAtEnd     结束时锁是否空闲且队列为空
     * @param conclusion        一句话结论
     */
    public record Result(int waiters, long holdMillis,
                         List<LockStep> lockSteps, List<TimelineEvent> timeline,
                         int maxQueueLength, List<Integer> queueLengthSamples,
                         List<String> acquisitionOrder, boolean allWaitersAcquired,
                         long elapsedMillis, Misuse misuse, boolean lockFreeAtEnd,
                         String conclusion) {
    }
}
