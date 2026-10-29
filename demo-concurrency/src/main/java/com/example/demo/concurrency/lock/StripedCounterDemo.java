package com.example.demo.concurrency.lock;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Service;

import com.example.demo.concurrency.support.DemoThreads;

/**
 * 进阶场景 2：分段计数（striped counter），也就是 LongAdder 的核心思路。
 *
 * <p>问题：高并发下所有线程都对同一个 {@link AtomicLong} 做 CAS，成功者只有一个，
 * 其余线程不断失败重试，缓存行还会在核心之间来回弹跳。分段计数把“一个热点变量”
 * 拆成多个 Cell，每个线程只更新自己那一个，最后求和时再合并。</p>
 *
 * <p>本类手写了一个最小版 {@link StripedCounter}：</p>
 * <ul>
 *   <li>Cell 数量向上取到 2 的幂，索引用 {@code 线程 id & mask}，避免取模开销；</li>
 *   <li>每个 Cell 是一个 volatile long，通过 {@link VarHandle} 做 CAS；</li>
 *   <li>{@link StripedCounter#sum()} 逐格读取再相加，不是原子快照——并发更新时可能少算。</li>
 * </ul>
 *
 * <p>对照 {@link AtomicLong} 和 JDK 的 {@link LongAdder}，用同一批线程、同样的迭代次数
 * 比较耗时与 Cell 分布。绝对吞吐依赖机器，只适合同机对比。</p>
 */
@Service
public class StripedCounterDemo {

    /** 每轮线程名编号：区分预热轮和正式测量轮，便于线程快照排查。 */
    private static final AtomicLong RUN_ID = new AtomicLong();

    /**
     * 对比 atomic / 手写分段 / JDK LongAdder 三种写法。
     *
     * @param threads    线程数
     * @param iterations 每线程迭代次数
     * @param cells      期望的 Cell 数量，会向上取到 2 的幂；1 表示退化成单点 CAS
     * @param rounds     正式测量轮数，取中位数抵消 GC / 调度抖动
     * @return 三种写法的结果、实际 Cell 数和最后一次测量的 Cell 分布
     */
    public Result compare(int threads, int iterations, int cells, int rounds) {
        List<ModeResult> modes = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            modes.add(runMode(mode, threads, iterations, cells, rounds));
        }
        int actualCells = StripedCounter.normalizeCells(cells);
        return new Result(threads, iterations, cells, actualCells, rounds, 2, List.copyOf(modes),
                "每种写法先跑 2 轮预热；线程按 threadId 的低位选择 Cell，"
                        + "因此相邻的演示线程通常会落到不同 Cell；sum() 不是原子快照，"
                        + "绝对吞吐依赖机器，只做同机对比");
    }

    /** 跑一种写法：预热 2 轮，再测 rounds 轮并取中位数。 */
    private ModeResult runMode(Mode mode, int threads, int iterations, int cells, int rounds) {
        long expectedPerRound = (long) threads * iterations;
        List<Long> actuals = new ArrayList<>(rounds);
        List<Long> elapsedNanos = new ArrayList<>(rounds);
        List<Long> lastDistribution = List.of();

        // JIT 未预热时第一次测量会明显偏慢，而且可能改变三种写法的排名，
        // 所以预热两轮且不计入中位数
        runRound(mode, threads, iterations, cells);
        runRound(mode, threads, iterations, cells);

        for (int round = 1; round <= rounds; round++) {
            RoundSample sample = runRound(mode, threads, iterations, cells);
            actuals.add(sample.actual());
            elapsedNanos.add(sample.elapsedNanos());
            lastDistribution = sample.distribution();
        }

        long medianNanos = median(elapsedNanos);
        double medianMillis = round1(medianNanos / 1_000_000.0);
        long medianActual = median(actuals);
        double opsPerMillis = round1(medianActual / Math.max(medianNanos / 1_000_000.0, 0.001));
        boolean exact = actuals.stream().allMatch(actual -> actual == expectedPerRound);
        return new ModeResult(mode.label, expectedPerRound, List.copyOf(actuals), exact,
                medianMillis, opsPerMillis, lastDistribution);
    }

    /** 跑一轮：每轮新建计数器和线程，避免上一轮的状态污染下一轮。 */
    private RoundSample runRound(Mode mode, int threads, int iterations, int cells) {
        AtomicLong atomic = new AtomicLong();
        StripedCounter striped = new StripedCounter(cells);
        LongAdder adder = new LongAdder();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        long runId = RUN_ID.incrementAndGet();
        long begin = System.nanoTime();

        for (int i = 0; i < threads; i++) {
            DemoThreads.start("striped-" + mode.label + "-" + runId + "-" + i, () -> {
                try {
                    start.await();
                    for (int k = 0; k < iterations; k++) {
                        switch (mode) {
                            // 单点 CAS：所有线程争同一个变量，失败后自旋重试
                            case ATOMIC -> atomic.incrementAndGet();
                            // 手写分段：每个线程优先更新自己的 Cell
                            case STRIPED -> striped.increment();
                            // JDK 实现：内部同样分段，还会在竞争加剧时扩容 Cell
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
            case ATOMIC -> atomic.get();
            case STRIPED -> striped.sum();
            case LONGADDER -> adder.sum();
        };
        List<Long> distribution = mode == Mode.STRIPED ? striped.snapshot() : List.of();
        return new RoundSample(actual, elapsed, distribution);
    }

    /** 中位数：排序后取中间；偶数个取偏后的那个，避免再引入小数。 */
    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        return sorted.get(sorted.size() / 2);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static void await(CountDownLatch latch, long millis) {
        try {
            latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 手写分段计数器。
     *
     * <p>真实 {@code LongAdder} 会在 CAS 冲突时扩容 Cell、用 {@code @Contended}
     * 填充避免伪共享；这里固定 Cell 数量，只保留最核心的“分散热点 + 最后求和”。</p>
     */
    static final class StripedCounter {

        private final Cell[] cells;
        private final int mask;

        StripedCounter(int requestedCells) {
            int size = normalizeCells(requestedCells);
            this.cells = new Cell[size];
            for (int i = 0; i < size; i++) {
                cells[i] = new Cell();
            }
            // size 已是 2 的幂，减一就是取模掩码：index & mask 比 % size 便宜
            this.mask = size - 1;
        }

        /** 把任意正整数向上取到 2 的幂；1 保持 1，用于观察“只有一格”的退化情况。 */
        static int normalizeCells(int requestedCells) {
            int size = 1;
            while (size < requestedCells) {
                size <<= 1;
            }
            return size;
        }

        /** 按线程 id 的低位选 Cell；同一线程总是命中同一格，CAS 不会和别人抢。 */
        void increment() {
            int index = (int) (Thread.currentThread().threadId() & mask);
            cells[index].increment();
        }

        /**
         * 求和：逐格读取后相加，没有全局锁。
         *
         * <p>因此并发调用时可能读到“某一格已经加了、另一格还没加”的中间状态，
         * 结果偏小是正常现象；需要精确瞬时值时应使用 AtomicLong 或加锁。</p>
         */
        long sum() {
            long total = 0;
            for (Cell cell : cells) {
                total += cell.value;
            }
            return total;
        }

        /** 返回每一格当前的计数，用于观察线程是否真的分散到了不同 Cell。 */
        List<Long> snapshot() {
            List<Long> values = new ArrayList<>(cells.length);
            for (Cell cell : cells) {
                values.add(cell.value);
            }
            return List.copyOf(values);
        }
    }

    /**
     * 一个计数槽位：volatile long + VarHandle CAS。
     *
     * <p>不用 AtomicLong 是为了贴近 LongAdder 的真实结构：Cell 本身就是一块
     * 可被 CAS 更新的内存，额外包一层对象只会增加间接寻址。</p>
     */
    static final class Cell {

        private static final VarHandle VALUE;

        static {
            try {
                VALUE = MethodHandles.lookup().findVarHandle(Cell.class, "value", long.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private volatile long value;

        /**
         * 一次原子加一。
         *
         * <p>这里用 {@code getAndAdd} 而不是手写 CAS 循环：两者语义相同，但
         * {@code getAndAdd} 会被 JIT 编译成单条 CPU 原子指令；手写循环在单 Cell
         * 高竞争时反而比 {@link AtomicLong} 慢很多，会干扰“分段是否有效”的对比。</p>
         */
        void increment() {
            VALUE.getAndAdd(this, 1L);
        }
    }

    /** 三种实现的名字与接口参数保持一致，避免文档和返回体对不上。 */
    private enum Mode {
        ATOMIC("atomic"),
        STRIPED("striped"),
        LONGADDER("longadder");

        private final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    /** 一轮的原始结果：实际计数、耗时与分段分布。 */
    private record RoundSample(long actual, long elapsedNanos, List<Long> distribution) {
    }

    /**
     * 一种写法的对比结果。
     *
     * @param mode                atomic / striped / longadder
     * @param expectedPerRound    每轮理论值 threads × iterations
     * @param actuals             每轮实际值
     * @param exact               每轮是否都精确等于理论值
     * @param medianElapsedMillis 中位耗时（毫秒）
     * @param opsPerMillis        有效自增/毫秒
     * @param cellDistribution    分段计数最后一次测量的每格计数；其他写法为空
     */
    public record ModeResult(String mode, long expectedPerRound, List<Long> actuals,
                             boolean exact, double medianElapsedMillis, double opsPerMillis,
                             List<Long> cellDistribution) {
    }

    /**
     * 分段计数对比结果。
     *
     * @param threads          线程数
     * @param iterations       每线程迭代次数
     * @param requestedCells   请求的 Cell 数量
     * @param actualCells      实际 Cell 数量（向上取到 2 的幂）
     * @param rounds           正式测量轮数
     * @param warmupRounds     排除在统计外的预热轮数
     * @param modes            三种写法的明细
     * @param note             统计口径与实现说明
     */
    public record Result(int threads, int iterations, int requestedCells, int actualCells,
                         int rounds, int warmupRounds, List<ModeResult> modes, String note) {
    }
}
