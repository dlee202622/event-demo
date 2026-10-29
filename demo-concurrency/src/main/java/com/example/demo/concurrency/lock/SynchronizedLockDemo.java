package com.example.demo.concurrency.lock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

import com.example.demo.concurrency.support.DemoThreads;
import com.example.demo.concurrency.support.Observations.Span;
import com.example.demo.concurrency.support.Observations.ThreadSnapshot;
import com.example.demo.concurrency.support.ThreadSampler;

/**
 * 场景 1：{@code synchronized} 的互斥效果与监视器（monitor）观测。
 *
 * <p>两个线程用 {@link CountDownLatch} 对齐起跑线，同时冲同一段临界区：</p>
 * <ul>
 *   <li>{@code locked=true}：临界区被 {@code synchronized(monitor)} 包住，两个线程串行，
 *       持锁期间另一个线程在 JVM 里是 {@code BLOCKED}，并且能查到锁的持有者；</li>
 *   <li>{@code locked=false}：去掉同步块，两个线程同时进入，最大并发变成 2。</li>
 * </ul>
 *
 * <p>观测手段是 JDK 自带的 {@code ThreadMXBean}：它能看到线程状态、正在等待的
 * monitor、以及 monitor 的持有者，不需要 JOL 或 JVM 调试参数。</p>
 *
 * <p>限制：{@code synchronized} 的锁对象必须是同一个引用；本实验每次请求新建
 * monitor，所以不同请求之间互不影响，可以并发调用。</p>
 */
@Service
public class SynchronizedLockDemo {

    /** 请求级编号：线程名带上它，ThreadMXBean 快照里才能区分不同请求的 A/B 线程。 */
    private static final AtomicLong RUN_ID = new AtomicLong();

    /**
     * 跑一次“两线程同时进临界区”的实验。
     *
     * @param locked     true 用 {@code synchronized} 包住临界区；false 裸跑作为反例
     * @param holdMillis 临界区内停留时间（毫秒）；越大越容易采样到 BLOCKED 状态
     * @return 进出时间线、最大并发数、采样时的线程快照和结论
     */
    public Result run(boolean locked, long holdMillis) {
        long runId = RUN_ID.incrementAndGet();
        // monitor 必须是所有竞争线程共享的同一个对象；每次请求新建，避免请求间串扰
        Object monitor = new Object();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch startGate = new CountDownLatch(1);
        // 用线程安全集合：两个 worker 会并发写入
        List<Span> spans = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        long startNanos = System.nanoTime();

        List<Thread> workers = new ArrayList<>(2);
        for (String role : List.of("A", "B")) {
            Runnable body = () -> {
                ready.countDown();
                try {
                    // 两个线程都就绪后才放行，尽量让它们同时到达锁，而不是被启动开销错开
                    startGate.await();
                    if (locked) {
                        // 互斥的边界就是这对大括号：同一时刻只允许一个线程持有 monitor
                        synchronized (monitor) {
                            enterCritical(role, holdMillis, startNanos, spans, concurrent, maxConcurrent);
                        }
                    } else {
                        // 反例：没有同步块，两个线程可以同时执行 enterCritical
                        enterCritical(role, holdMillis, startNanos, spans, concurrent, maxConcurrent);
                    }
                } catch (InterruptedException e) {
                    // worker 被中断时恢复标记，交给线程结束流程；本实验不主动中断 worker
                    Thread.currentThread().interrupt();
                }
            };
            workers.add(DemoThreads.start("sync-" + runId + "-" + role, body));
        }

        await(ready, 2000);
        startGate.countDown();

        // 在半程采样：持锁线程正在 sleep，另一个线程要么 BLOCKED（加锁），要么也在 sleep（裸跑）
        try {
            Thread.sleep(Math.max(holdMillis / 2, 30));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        List<ThreadSnapshot> snapshots = ThreadSampler.sampleAll(workers);

        // 给 worker 留出“正常结束”的余量；join 超时也不会让请求无限挂住
        for (Thread worker : workers) {
            DemoThreads.join(worker, holdMillis + 2000);
        }
        spans.sort(Comparator.comparingLong(Span::enterAtMillis));

        int max = maxConcurrent.get();
        String conclusion;
        if (locked && max == 1) {
            conclusion = "synchronized 生效：两个线程串行进入，最大并发=1";
        } else if (!locked && max >= 2) {
            conclusion = "没有互斥：两个线程同时进入临界区，最大并发=" + max;
        } else {
            // 正常参数下不应出现；保留分支是为了让异常结果有明确说明，而不是静默返回
            conclusion = "结果与预期不符（maxConcurrent=" + max + "），可重跑或加大 holdMillis";
        }
        return new Result(locked, holdMillis, max, spans, snapshots,
                elapsedMillis(startNanos), conclusion);
    }

    /**
     * 临界区体：进入时记时间、累加并发数，退出时记时间并归还并发计数。
     *
     * <p>并发计数只用于教学观测，不是业务逻辑；真正的互斥由调用处的
     * {@code synchronized} 提供。</p>
     */
    private void enterCritical(String role, long holdMillis, long startNanos,
                               List<Span> spans, AtomicInteger concurrent,
                               AtomicInteger maxConcurrent) throws InterruptedException {
        int current = concurrent.incrementAndGet();
        // accumulateAndGet 把“读最大值 + 写回”合成一个原子操作，避免观测值被并发覆盖
        maxConcurrent.accumulateAndGet(current, Math::max);
        long enterAt = elapsedMillis(startNanos);
        try {
            // 模拟业务执行：睡在这里，才能稳定观察到另一个线程被挡在锁外
            Thread.sleep(holdMillis);
        } finally {
            // 用 finally 保证即使 sleep 被中断，也会记录退出并释放观测计数
            spans.add(new Span(role, enterAt, elapsedMillis(startNanos)));
            concurrent.decrementAndGet();
        }
    }

    /** 把纳秒起点换算成相对毫秒，返回体里所有时间点都使用这个基准。 */
    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** 等待 latch，超时或中断都继续执行，让接口最终能返回可诊断的结果。 */
    private static void await(CountDownLatch latch, long millis) {
        try {
            latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 实验结果。
     *
     * @param locked       true 表示走了 synchronized 分支
     * @param holdMillis   临界区停留时间
     * @param maxConcurrent 采样期间观测到的最大并发线程数，1 表示互斥成立
     * @param spans        每个线程进入/退出临界区的相对毫秒时间
     * @param snapshots    采样瞬间的线程状态；加锁时能看到一个 BLOCKED + lockOwner
     * @param elapsedMillis 实验总耗时
     * @param conclusion   一句话结论，便于直接断言/阅读
     */
    public record Result(boolean locked, long holdMillis, int maxConcurrent,
                         List<Span> spans, List<ThreadSnapshot> snapshots,
                         long elapsedMillis, String conclusion) {
    }
}
