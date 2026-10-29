package com.example.demo.concurrency.lock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Service;

import com.example.demo.concurrency.support.DemoThreads;
import com.example.demo.concurrency.support.Observations.ThreadSnapshot;
import com.example.demo.concurrency.support.Observations.TimelineEvent;
import com.example.demo.concurrency.support.ThreadSampler;

/**
 * 场景 4：阻塞中的线程能否被中断——{@code synchronized} vs {@code lockInterruptibly()}。
 *
 * <p>同一套时序跑两种锁：持有者先拿锁并睡 {@code holdMillis}，受害者随后尝试进入，
 * 在阻塞 {@code interruptAfterMillis} 毫秒后被 {@code interrupt()}。</p>
 * <ul>
 *   <li>{@code synchronized}：monitorenter 不可中断。中断只是把标记置为 true，
 *       受害者仍要等持有者释放后才能进入临界区；</li>
 *   <li>{@code lockInterruptibly()}：等待期间收到中断立即抛 {@link InterruptedException}，
 *       不会进入临界区，响应时间通常在毫秒级。</li>
 * </ul>
 *
 * <p>坑：{@code synchronized} 的“不可中断”经常被误解为“中断无效”。中断标记其实已经置上，
 * 只是进入 monitor 的动作不会因此放弃；如果线程之后调用 sleep/wait/join，
 * 会立刻抛出 InterruptedException。生产代码若要支持取消/超时，优先用可中断或带超时的锁。</p>
 */
@Service
public class InterruptDemo {

    /** 请求级编号，用于区分并发请求的线程名。 */
    private static final AtomicLong RUN_ID = new AtomicLong();

    /**
     * 跑一次中断实验。
     *
     * @param mode                锁类型：SYNCHRONIZED 或 REENTRANT
     * @param holdMillis          持有者持锁时间（毫秒）；必须明显大于中断延迟
     * @param interruptAfterMillis 受害者确认阻塞后再等多久才中断（毫秒）
     * @return 受害者是否进入临界区、中断响应耗时、阻塞时快照与时间线
     */
    public Result run(Mode mode, long holdMillis, long interruptAfterMillis) {
        long runId = RUN_ID.incrementAndGet();
        Object monitor = new Object();
        ReentrantLock lock = new ReentrantLock();
        CountDownLatch holderHeld = new CountDownLatch(1);
        CountDownLatch victimReady = new CountDownLatch(1);
        CountDownLatch victimAttempting = new CountDownLatch(1);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<TimelineEvent> timeline = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean victimEntered = new AtomicBoolean(false);
        AtomicBoolean exceptionThrown = new AtomicBoolean(false);
        AtomicLong enteredAtMillis = new AtomicLong(-1);
        AtomicLong interruptAtMillis = new AtomicLong(-1);
        AtomicLong interruptLatencyMillis = new AtomicLong(-1);
        AtomicReference<String> exceptionType = new AtomicReference<>();
        long startNanos = System.nanoTime();

        Thread holder = DemoThreads.start("int-" + runId + "-holder", () -> {
            try {
                if (mode == Mode.SYNCHRONIZED) {
                    synchronized (monitor) {
                        holderHeld.countDown();
                        timeline.add(new TimelineEvent(elapsedMillis(startNanos), "holder",
                                "acquired", "monitor"));
                        sleepQuietly(holdMillis);
                    }
                } else {
                    lock.lock();
                    try {
                        holderHeld.countDown();
                        timeline.add(new TimelineEvent(elapsedMillis(startNanos), "holder",
                                "acquired", "ReentrantLock"));
                        sleepQuietly(holdMillis);
                    } finally {
                        lock.unlock();
                    }
                }
                timeline.add(new TimelineEvent(elapsedMillis(startNanos), "holder", "released", ""));
            } finally {
                done.countDown();
            }
        });

        Thread victim = DemoThreads.start("int-" + runId + "-victim", () -> {
            victimReady.countDown();
            try {
                startGate.await();
                // 关键：告诉主线程“已经越过起跑闸门，马上开始抢锁”。
                // 否则主线程可能把 startGate.await() 的 WAITING 误判成“正在等锁”。
                victimAttempting.countDown();
                timeline.add(new TimelineEvent(elapsedMillis(startNanos), "victim", "attempt", mode.name()));
                if (mode == Mode.SYNCHRONIZED) {
                    // 不可中断：即使线程的中断标记为 true，也要等 monitor 释放后才返回
                    synchronized (monitor) {
                        victimEntered.set(true);
                        enteredAtMillis.set(elapsedMillis(startNanos));
                        timeline.add(new TimelineEvent(elapsedMillis(startNanos), "victim",
                                "entered", "interrupt 未阻止进入"));
                    }
                } else {
                    try {
                        lock.lockInterruptibly();
                        try {
                            victimEntered.set(true);
                            enteredAtMillis.set(elapsedMillis(startNanos));
                            timeline.add(new TimelineEvent(elapsedMillis(startNanos), "victim",
                                    "entered", "未在等待期被中断"));
                        } finally {
                            lock.unlock();
                        }
                    } catch (InterruptedException e) {
                        // 可中断锁的等待是 park 状态，interrupt 会让 park 立即返回并清除中断标记
                        exceptionThrown.set(true);
                        exceptionType.set(e.getClass().getSimpleName());
                        interruptLatencyMillis.set(elapsedMillis(startNanos) - interruptAtMillis.get());
                        timeline.add(new TimelineEvent(elapsedMillis(startNanos), "victim",
                                "interrupted", "抛 InterruptedException，未进入临界区"));
                    }
                }
            } catch (InterruptedException e) {
                // 只可能中断在 startGate.await 上；恢复标记并结束
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });

        await(holderHeld, 2000);
        await(victimReady, 2000);
        startGate.countDown();
        // 等受害者真正越过闸门后，再判断它是不是卡在锁上；否则会把闸门等待误认为锁等待
        await(victimAttempting, 1000);

        // 等受害者真正阻塞再采样：BLOCKED 表示卡在 monitorenter，WAITING 表示卡在 AQS park
        long stateDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < stateDeadline
                && victim.getState() != Thread.State.BLOCKED
                && victim.getState() != Thread.State.WAITING) {
            sleepQuietly(5);
        }
        ThreadSnapshot blockedSnapshot = ThreadSampler.sample(victim);

        // 从“确认阻塞”再等一会儿才中断，确保快照和中断都发生在等待期内
        sleepQuietly(interruptAfterMillis);
        interruptAtMillis.set(elapsedMillis(startNanos));
        victim.interrupt();

        await(done, holdMillis + 3000);
        DemoThreads.join(holder, 2000);
        DemoThreads.join(victim, 2000);
        timeline.sort(Comparator.comparingLong(TimelineEvent::atMillis));

        boolean entered = victimEntered.get();
        boolean thrown = exceptionThrown.get();
        // InterruptedException 会清除中断标记；synchronized 分支的标记则保留下来
        boolean interruptFlagAfter = victim.isInterrupted();
        String conclusion;
        if (mode == Mode.SYNCHRONIZED) {
            conclusion = entered
                    ? "synchronized 不可中断：中断只置标记，受害者在锁释放后才进入临界区"
                    : "受害者未进入临界区，请检查 holdMillis 是否过短";
        } else {
            conclusion = thrown
                    ? "lockInterruptibly 可中断：等待约 " + interruptLatencyMillis.get()
                    + "ms 后抛出 InterruptedException，未进入临界区"
                    : "受害者未在等待期被中断，请调大 holdMillis 或 interruptAfterMillis";
        }
        return new Result(mode.name().toLowerCase(Locale.ROOT), holdMillis, interruptAfterMillis,
                entered, enteredAtMillis.get(), thrown, exceptionType.get(),
                interruptLatencyMillis.get(), interruptFlagAfter, blockedSnapshot,
                List.copyOf(timeline), conclusion);
    }

    /** 把纳秒起点换算成相对毫秒。 */
    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** 等待 latch；超时或中断都继续，让接口能返回诊断结果。 */
    private static void await(CountDownLatch latch, long millis) {
        try {
            latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 睡眠；被中断时恢复标记，不吞掉中断。 */
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 锁类型。URL 参数用 {@link #from(String)} 解析，错误信息会列出可选值。
     */
    public enum Mode {
        /** 对象监视器：进入同步块时 monitorenter，不可中断。 */
        SYNCHRONIZED,
        /** ReentrantLock 的可中断等待：park 期间响应 interrupt。 */
        REENTRANT;

        /**
         * 解析 URL 参数，忽略大小写。
         *
         * @param raw 原始参数，例如 synchronized / reentrant
         * @return 对应枚举
         * @throws IllegalArgumentException 参数为空或不在可选值内
         */
        public static Mode from(String raw) {
            if (raw == null) {
                throw new IllegalArgumentException("mode 不能为空，可选值: synchronized, reentrant");
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "synchronized", "sync" -> SYNCHRONIZED;
                case "reentrant", "reentrantlock" -> REENTRANT;
                default -> throw new IllegalArgumentException(
                        "mode 只能是 synchronized 或 reentrant，当前=" + raw);
            };
        }
    }

    /**
     * 中断实验结果。
     *
     * @param mode                  实际执行的锁类型
     * @param holdMillis            持有者持锁时间
     * @param interruptAfterMillis  确认阻塞后到中断的等待时间
     * @param victimEntered         受害者最终是否进入临界区
     * @param enteredAtMillis       进入临界区的相对时间；未进入为 -1
     * @param exceptionThrown       是否抛出 InterruptedException
     * @param exceptionType         异常类型；未抛出为 null
     * @param interruptLatencyMillis 从 interrupt() 到捕获异常的毫秒数；不可中断时为 -1
     * @param interruptFlagAfter    实验结束时受害者的中断标记是否仍为 true
     * @param blockedSnapshot       中断前受害者的线程快照（应为 BLOCKED 或 WAITING）
     * @param timeline              时间线
     * @param conclusion            一句话结论
     */
    public record Result(String mode, long holdMillis, long interruptAfterMillis,
                         boolean victimEntered, long enteredAtMillis,
                         boolean exceptionThrown, String exceptionType,
                         long interruptLatencyMillis, boolean interruptFlagAfter,
                         ThreadSnapshot blockedSnapshot, List<TimelineEvent> timeline,
                         String conclusion) {
    }
}
