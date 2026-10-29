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

/**
 * 场景 3：公平锁与非公平锁在“释放瞬间再次竞争”时的差异。
 *
 * <p>实验编排（每一轮）：</p>
 * <pre>
 * t0  holder 拿到锁
 * t1  N 个 waiter 依次启动并入队（每启动一个都等到队列长度 +1，固定排队顺序）
 * t2  holder unlock() 后立刻再 lock() 一次
 *     - 公平锁：发现队列非空，排到队尾，最后才拿到锁
 *     - 非公平锁：队列检查被跳过，直接用 CAS 抢锁，通常插到所有 waiter 前面
 * </pre>
 *
 * <p>「释放者立刻再抢」是演示插队最稳定的编排：它不需要赌线程调度，
 * 只需要观察 holder 第二次拿锁的位置。非公平锁允许插队是吞吐更高的原因之一，
 * 代价是等待时间不可预测（可能饥饿）。</p>
 *
 * <p>注意：公平锁只保证“排队者按 FIFO 获得锁”，不保证线程调度的公平，
 * 也不保证 {@code tryLock()} 公平——{@code tryLock()} 在公平锁上同样可以插队。</p>
 */
@Service
public class FairnessDemo {

    /** 请求级编号，避免多轮/多次请求的线程名混在一起。 */
    private static final AtomicLong RUN_ID = new AtomicLong();
    /** 持有者在释放后立刻重抢时使用的名字，结果里单独标记。 */
    private static final String HOLDER_REACQUIRE = "holder-reacquire";

    /**
     * 跑若干轮公平性实验。
     *
     * @param fair    true 使用 {@code new ReentrantLock(true)}；false 使用默认非公平锁
     * @param threads 等待者数量（每轮新建）
     * @param rounds  轮数；非公平锁的插队是概率事件，多跑几轮更容易观察
     * @return 每轮的排队顺序、实际获取顺序、holder 位置与插队轮数
     */
    public Result run(boolean fair, int threads, int rounds) {
        long runId = RUN_ID.incrementAndGet();
        List<RoundResult> results = new ArrayList<>(rounds);
        for (int round = 1; round <= rounds; round++) {
            results.add(oneRound(fair, threads, runId, round));
        }
        int bargedRounds = (int) results.stream().filter(RoundResult::holderBarged).count();
        String conclusion;
        if (fair) {
            conclusion = "公平锁：holder 重抢时排到队尾（位置=" + (threads + 1)
                    + "），等待者保持入队顺序";
        } else if (bargedRounds > 0) {
            conclusion = "非公平锁：释放者在 " + bargedRounds + "/" + rounds
                    + " 轮中插队成功（holder 位置=1）";
        } else {
            conclusion = "非公平锁：本轮未观察到插队，可增加 rounds 重跑；公平锁行为不受影响";
        }
        return new Result(fair, threads, rounds, results, bargedRounds, conclusion);
    }

    /** 跑一轮：固定排队顺序，然后让 holder 在释放瞬间重抢，记录最终获取顺序。 */
    private RoundResult oneRound(boolean fair, int threads, long runId, int round) {
        ReentrantLock lock = new ReentrantLock(fair);
        CountDownLatch holderLocked = new CountDownLatch(1);
        CountDownLatch queueReady = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads + 1);
        List<String> acquisitions = Collections.synchronizedList(new ArrayList<>());
        List<String> scheduled = new ArrayList<>(threads);
        long startNanos = System.nanoTime();
        List<Thread> workers = new ArrayList<>(threads + 1);

        Thread holder = DemoThreads.start("fair-" + runId + "-r" + round + "-holder", () -> {
            lock.lock();
            try {
                holderLocked.countDown();
                try {
                    queueReady.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                lock.unlock();
                // 关键动作：释放后不睡眠，立刻再次 lock()。公平锁会排队，非公平锁会尝试插队
                lock.lock();
                try {
                    acquisitions.add(HOLDER_REACQUIRE);
                    // 短暂持有，确保“谁先拿到”已经记录完毕，再让后面的线程继续
                    sleepQuietly(20);
                } finally {
                    lock.unlock();
                }
            } finally {
                // 兜底：如果上面的流程异常退出，保证不会把锁泄漏给后续轮次
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
                done.countDown();
            }
        });
        workers.add(holder);

        await(holderLocked, 2000);

        // 一次只启动一个 waiter，并等它真正入队后再启动下一个，从而固定“排队顺序”
        for (int i = 1; i <= threads; i++) {
            String name = "waiter-" + i;
            scheduled.add(name);
            Thread waiter = DemoThreads.start("fair-" + runId + "-r" + round + "-w" + i, () -> {
                try {
                    lock.lock();
                    try {
                        acquisitions.add(name);
                        // 持有一小会，避免多个 waiter 在同一毫秒内完成导致顺序难以观察
                        sleepQuietly(2);
                    } finally {
                        lock.unlock();
                    }
                } finally {
                    done.countDown();
                }
            });
            workers.add(waiter);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (lock.getQueueLength() < i && System.nanoTime() < deadline) {
                sleepQuietly(2);
            }
        }

        int maxQueueLength = lock.getQueueLength();
        queueReady.countDown();
        await(done, 5000);
        for (Thread worker : workers) {
            DemoThreads.join(worker, 2000);
        }

        List<String> waiterOrder = acquisitions.stream()
                .filter(name -> !HOLDER_REACQUIRE.equals(name))
                .toList();
        int holderPosition = acquisitions.indexOf(HOLDER_REACQUIRE) + 1;
        int mismatches = 0;
        for (int i = 0; i < Math.min(waiterOrder.size(), scheduled.size()); i++) {
            if (!waiterOrder.get(i).equals(scheduled.get(i))) {
                mismatches++;
            }
        }

        return new RoundResult(round, List.copyOf(scheduled), List.copyOf(acquisitions),
                holderPosition, holderPosition == 1, mismatches,
                maxQueueLength, elapsedMillis(startNanos));
    }

    /** 把纳秒起点换算成相对毫秒。 */
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

    /** 短睡眠；被中断时恢复标记。 */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 一轮的实验结果。
     *
     * @param round                 轮次，从 1 开始
     * @param scheduledWaiters      按启动顺序记录的等待者，代表 AQS 队列顺序
     * @param acquisitionOrder      实际获取顺序，包含 holder-reacquire
     * @param holderPosition        holder 第二次拿锁的位置（1 表示插到最前）
     * @param holderBarged          holder 是否插队成功
     * @param waiterOrderMismatches waiter 实际顺序与排队顺序不一致的次数
     * @param maxQueueLength        释放前采样到的队列长度
     * @param elapsedMillis         本轮耗时
     */
    public record RoundResult(int round, List<String> scheduledWaiters,
                              List<String> acquisitionOrder, int holderPosition,
                              boolean holderBarged, int waiterOrderMismatches,
                              int maxQueueLength, long elapsedMillis) {
    }

    /**
     * 公平性实验结果。
     *
     * @param fair        是否公平锁
     * @param threads     每轮等待者数量
     * @param rounds      轮数
     * @param roundResults 每轮明细
     * @param bargedRounds holder 插队成功的轮数
     * @param conclusion  一句话结论
     */
    public record Result(boolean fair, int threads, int rounds,
                         List<RoundResult> roundResults, int bargedRounds,
                         String conclusion) {
    }
}
