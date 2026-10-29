package com.example.demo.concurrency.lock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Service;

import com.example.demo.concurrency.support.DemoThreads;

/**
 * 场景 6：读-改-写为什么会丢更新，以及 synchronized / AtomicLong / LongAdder 的取舍。
 *
 * <p>两部分实验：</p>
 * <ol>
 *   <li>{@link #lostUpdates}：用两道 {@link CyclicBarrier} 把“读”和“写”之间的窗口
 *       放大到必然重叠——所有线程读到同一个值后再一起写回，每轮只真正 +1。
 *       结果不是概率性的，expected 与 actual 的差值可以精确断言。</li>
 *   <li>{@link #compare}：让四种写法跑同样的自然竞争流量，比较“结果是否正确”和
 *       “有效自增/毫秒”。吞吐受机器和 JIT 影响，只适合同机对比，不承诺绝对数字。</li>
 * </ol>
 *
 * <p>选型提示：</p>
 * <ul>
 *   <li>低竞争、需要返回值：{@link AtomicLong}；</li>
 *   <li>高竞争、只做计数/求和：{@link LongAdder}（分段累加，最后 sum 合并）；</li>
 *   <li>临界区里还有多项操作：锁（synchronized 或 ReentrantLock），原子类只能保护一个变量；</li>
 *   <li>无锁代码只适合“单个原子操作”，{@code count++} 不是原子操作。</li>
 * </ul>
 */
@Service
public class CounterDemo {

    /** 每轮线程名编号：线程名带编号，排查时能区分预热轮和正式测量轮。 */
    private static final AtomicLong RUN_ID = new AtomicLong();

    /**
     * 确定性丢更新实验。
     *
     * <p>每个线程每一轮都先读、再等所有线程读完、然后写回同一个值，
     * 因此一轮只会产生一次有效自增：actual 应当等于 iterations，
     * 而不是 threads × iterations。</p>
     *
     * @param threads    线程数，至少 2
     * @param iterations 每线程迭代次数
     * @return 期望值、实际值、丢失量与耗时；orchestrated=true 表示这是编排后的确定性结果
     */
    public LostUpdateResult lostUpdates(int threads, int iterations) {
        long expected = (long) threads * iterations;
        // 用数组包一层是因为 lambda 只能捕获 effectively final 的局部变量；元素本身不是原子的
        long[] value = {0};
        CyclicBarrier barrier = new CyclicBarrier(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        long startNanos = System.nanoTime();

        for (int i = 0; i < threads; i++) {
            DemoThreads.start("lost-" + i, () -> {
                try {
                    start.await();
                    for (int k = 0; k < iterations; k++) {
                        long read = value[0];
                        // 第一道栅栏：所有线程都完成“读”之后才继续，保证大家读到同一个旧值
                        barrier.await(10, TimeUnit.SECONDS);
                        value[0] = read + 1;
                        // 第二道栅栏：等所有“写”完成，避免下一轮读到半成品；环环相扣才是确定性的
                        barrier.await(10, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (BrokenBarrierException | TimeoutException e) {
                    // 有线程超时会导致栅栏损坏，其他线程也会从这里退出；接口返回已观测到的值
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        await(done, 60_000);
        long actual = value[0];
        return new LostUpdateResult(threads, iterations, expected, actual, expected - actual,
                elapsedMillis(startNanos), true,
                "两道栅栏把读改写窗口放大到必然重叠：每轮所有线程写回同一个值，只 +1");
    }

    /**
     * 四种计数写法跑同样的自然竞争流量，输出每轮实际值与中位耗时。
     *
     * @param threads    线程数
     * @param iterations 每线程迭代次数
     * @param rounds     每种写法跑几轮；取中位数降低 GC / 调度抖动
     * @return 每种写法的期望值、各轮实际值、是否精确、中位耗时与有效 ops/ms
     */
    public CompareResult compare(int threads, int iterations, int rounds) {
        List<ModeResult> modes = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            modes.add(runMode(mode, threads, iterations, rounds));
        }
        return new CompareResult(threads, iterations, rounds, 2, List.copyOf(modes),
                "每种写法先跑 2 轮预热（不计入中位数），再测 " + rounds
                        + " 轮取中位数；none 的 actual 可能小于 expected，说明丢了更新；"
                        + "绝对数字依赖机器，只做同机对比");
    }

    /** 跑一种计数写法：先预热 2 轮，再测 rounds 轮并取中位数。 */
    private ModeResult runMode(Mode mode, int threads, int iterations, int rounds) {
        long expectedPerRound = (long) threads * iterations;
        List<Long> actuals = new ArrayList<>(rounds);
        List<Long> elapsedNanos = new ArrayList<>(rounds);

        // JIT 只编译热点循环：1 轮预热在核数多、竞争激烈时仍可能来不及 C2 编译，
        // 同一台机器前后两次请求甚至可能给出相反的排名，所以预热 2 轮且不计入统计
        runRound(mode, threads, iterations);
        runRound(mode, threads, iterations);

        for (int round = 1; round <= rounds; round++) {
            RoundSample sample = runRound(mode, threads, iterations);
            actuals.add(sample.actual());
            elapsedNanos.add(sample.elapsedNanos());
        }

        long medianNanos = median(elapsedNanos);
        double medianMillis = round1(medianNanos / 1_000_000.0);
        long medianActual = median(actuals);
        // 用“有效自增”而不是“尝试次数”做分子：none 丢了更新，吞吐数字要反映真正成功的 +1
        double opsPerMillis = round1(medianActual / Math.max(medianNanos / 1_000_000.0, 0.001));
        boolean exact = actuals.stream().allMatch(actual -> actual == expectedPerRound);
        return new ModeResult(mode.label, expectedPerRound, List.copyOf(actuals), exact,
                medianMillis, opsPerMillis);
    }

    /** 跑一轮：每轮新建计数器和线程，避免上一轮状态影响下一轮。 */
    private RoundSample runRound(Mode mode, int threads, int iterations) {
        long[] plain = {0};
        AtomicLong atomic = new AtomicLong();
        LongAdder adder = new LongAdder();
        Object monitor = new Object();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        long runId = RUN_ID.incrementAndGet();
        long begin = System.nanoTime();

        for (int i = 0; i < threads; i++) {
            DemoThreads.start("cmp-" + mode.label + "-" + runId + "-" + i, () -> {
                try {
                    start.await();
                    for (int k = 0; k < iterations; k++) {
                        switch (mode) {
                            // 反例：读和写是两步，中间可能被其他线程插入
                            case NONE -> plain[0]++;
                            // 锁把“读 + 写”包成一个临界区，代价是线程可能排队
                            case SYNCHRONIZED -> {
                                synchronized (monitor) {
                                    plain[0]++;
                                }
                            }
                            // CAS 自旋：不加锁，但高竞争时失败重试会浪费 CPU
                            case ATOMIC -> atomic.incrementAndGet();
                            // 分段累加：不同线程通常更新不同 Cell，最后 sum 合并
                            case LONGADDER -> adder.increment();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        await(done, 60_000);
        long elapsed = System.nanoTime() - begin;
        long actual = switch (mode) {
            case NONE, SYNCHRONIZED -> plain[0];
            case ATOMIC -> atomic.get();
            case LONGADDER -> adder.sum();
        };
        return new RoundSample(actual, elapsed);
    }

    /** 中位数：排序后取中间；偶数个时取偏后的那个，避免额外平均引入小数。 */
    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        return sorted.get(sorted.size() / 2);
    }

    /** 保留一位小数，返回体里的耗时/吞吐不需要浮点噪声。 */
    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** 纳秒起点换算成相对毫秒。 */
    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    /** 等待所有 worker；超时也返回，让接口给出不完整但可诊断的结果。 */
    private static void await(CountDownLatch latch, long millis) {
        try {
            latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 四种计数实现。{@code label} 直接用接口参数里的名字，避免文档和返回体对不上。
     */
    private enum Mode {
        NONE("none"),
        SYNCHRONIZED("synchronized"),
        ATOMIC("atomic"),
        LONGADDER("longadder");

        private final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    /** 一轮的原始结果：实际计数与耗时，供预热轮丢弃、正式轮统计。 */
    private record RoundSample(long actual, long elapsedNanos) {
    }

    /**
     * 确定性丢更新结果。
     *
     * @param threads      线程数
     * @param iterations   每线程迭代次数
     * @param expected     理论值 threads × iterations
     * @param actual       实际值；orchestrated 模式下等于 iterations
     * @param lost         丢失的更新数
     * @param elapsedMillis 实验耗时
     * @param orchestrated 是否使用栅栏编排
     * @param note         解释
     */
    public record LostUpdateResult(int threads, int iterations, long expected, long actual,
                                   long lost, long elapsedMillis, boolean orchestrated,
                                   String note) {
    }

    /**
     * 一种计数写法的对比结果。
     *
     * @param mode                 none / synchronized / atomic / longadder
     * @param expectedPerRound     每轮理论值
     * @param actuals              每轮实际值，按轮次排列
     * @param exact                每轮是否都精确等于理论值
     * @param medianElapsedMillis  中位耗时（毫秒）
     * @param opsPerMillis         有效自增/毫秒（中位 actual ÷ 中位耗时）
     */
    public record ModeResult(String mode, long expectedPerRound, List<Long> actuals,
                             boolean exact, double medianElapsedMillis, double opsPerMillis) {
    }

    /**
     * 四种写法的横向对比结果。
     *
     * @param threads    线程数
     * @param iterations 每线程迭代次数
     * @param rounds     每种写法的测量轮数
     * @param warmupRounds 每种写法排除在统计外的预热轮数
     * @param modes      四种写法的明细，顺序与枚举一致
     * @param note       统计口径说明
     */
    public record CompareResult(int threads, int iterations, int rounds, int warmupRounds,
                                List<ModeResult> modes, String note) {
    }
}
