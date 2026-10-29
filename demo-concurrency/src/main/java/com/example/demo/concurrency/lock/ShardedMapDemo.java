package com.example.demo.concurrency.lock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.stereotype.Service;

import com.example.demo.concurrency.support.DemoThreads;

/**
 * 进阶场景 3：分片锁（sharded lock）保护一个 HashMap。
 *
 * <p>这是经典“分段锁”思路的最小实现：不再用一把大锁保护整张表，而是准备 N 把
 * {@link ReentrantLock}，按 key 的哈希值把操作路由到其中一把。不同分片上的 key
 * 可以真正并行，同一分片上的 key 仍然互斥。</p>
 *
 * <p>三个对照模式：</p>
 * <ul>
 *   <li>{@code global}：一把全局锁，所有 key 串行；</li>
 *   <li>{@code sharded}：16 把分片锁，普通 key 分散到不同分片；</li>
 *   <li>{@code hotkey}：同样 16 把锁，但所有线程都操作同一个 key——分片救不了热点，
 *       这是分片锁最重要的边界。</li>
 * </ul>
 *
 * <p>为了让“并行/串行”在耗时上可见，每次操作在锁内忙等 {@code workMicros} 微秒。
 * 不用 sleep 是因为操作系统睡眠粒度通常在毫秒级，会盖过锁本身的行为。</p>
 *
 * <p>注意：分片锁只保证单个分片内的操作原子；跨分片操作（例如同时修改两个 key）
 * 仍需要额外的一致性方案。更关键的是，<strong>每个分片必须各持一张 HashMap</strong>：
 * 如果所有分片共用一张 HashMap，不同分片并发 put 仍会同时修改哈希表结构（扩容、链表），
 * 锁并没有保护到共享数据。真实 ConcurrentHashMap 1.7 的 Segment 正是“一把锁 + 一张表”；
 * 1.8 之后改为 CAS + synchronized 锁单个桶，思想仍然是“缩小锁的粒度”。</p>
 */
@Service
public class ShardedMapDemo {

    /** 每次实验的编号，保证线程名在多次请求之间可区分。 */
    private static final AtomicLong RUN_ID = new AtomicLong();

    /**
     * 依次跑 global / sharded / hotkey 三种模式并返回对比。
     *
     * @param threads    线程数
     * @param keys       普通模式下使用的 key 总数
     * @param operations 每线程操作次数
     * @param shards     分片数（global 模式固定为 1）
     * @param workMicros 锁内忙等时长（微秒），模拟真实业务耗时
     * @return 三种模式的并发度、耗时、校验和与分片分布
     */
    public Result run(int threads, int keys, int operations, int shards, int workMicros) {
        List<ModeResult> modes = new ArrayList<>();
        for (Mode mode : Mode.values()) {
            modes.add(runMode(mode, threads, keys, operations, shards, workMicros));
        }
        ModeResult global = modes.get(0);
        ModeResult sharded = modes.get(1);
        ModeResult hotkey = modes.get(2);
        String conclusion = "全局锁 maxConcurrent=" + global.maxConcurrent()
                + "；分片锁 maxConcurrent=" + sharded.maxConcurrent()
                + "，耗时约为全局锁的 " + ratio(sharded.elapsedMillis(),
                global.elapsedMillis())
                + "；热点 key 模式 maxConcurrent=" + hotkey.maxConcurrent()
                + "，仍然退化为单锁";
        return new Result(threads, keys, operations, shards, workMicros, List.copyOf(modes),
                conclusion,
                "三种模式各自使用全新的锁与分片表（每个分片一张 HashMap）；锁内忙等 "
                        + workMicros + "µs 模拟业务；sum 必须等于 threads × operations");
    }

    /** 跑一种模式：每轮独立建 map / 锁 / 计数器，避免上一轮影响下一轮。 */
    private ModeResult runMode(Mode mode, int threads, int keys, int operations,
                               int shards, int workMicros) {
        int shardCount = mode == Mode.GLOBAL ? 1 : shards;
        ReentrantLock[] locks = new ReentrantLock[shardCount];
        // 每个分片各持一张 HashMap：锁保护的是“本分片的表”，而不是一张全局表。
        // 共用一张 HashMap 时，不同分片会并发改同一张表的结构，锁就形同虚设。
        List<Map<String, Integer>> maps = new ArrayList<>(shardCount);
        for (int i = 0; i < shardCount; i++) {
            locks[i] = new ReentrantLock();
            maps.add(new HashMap<>());
        }
        long[] shardOps = new long[shardCount];
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger cursor = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        long runId = RUN_ID.incrementAndGet();
        long begin = System.nanoTime();

        for (int i = 0; i < threads; i++) {
            DemoThreads.start("shard-" + mode.label + "-" + runId + "-" + i, () -> {
                try {
                    start.await();
                    for (int op = 0; op < operations; op++) {
                        // hotkey 模式故意让所有线程抢同一个 key；普通模式用全局游标
                        // 轮流分配 key，保证不同线程尽量打到不同分片
                        String key = mode == Mode.HOTKEY
                                ? "hot"
                                : "k" + Math.floorMod(cursor.getAndIncrement(), keys);
                        int shard = Math.floorMod(key.hashCode(), shardCount);
                        locks[shard].lock();
                        try {
                            int now = active.incrementAndGet();
                            maxConcurrent.accumulateAndGet(now, Math::max);
                            spinMicros(workMicros);
                            maps.get(shard).merge(key, 1, Integer::sum);
                            shardOps[shard]++;
                        } finally {
                            // 先减 active 再 unlock：否则会统计到“已经离开临界区但锁还没放”的假并发
                            active.decrementAndGet();
                            locks[shard].unlock();
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
        long elapsedNanos = System.nanoTime() - begin;
        int expectedSum = threads * operations;
        int actualSum = 0;
        int mapSize = 0;
        for (Map<String, Integer> shardMap : maps) {
            actualSum += shardMap.values().stream().mapToInt(Integer::intValue).sum();
            mapSize += shardMap.size();
        }
        int expectedKeys = mode == Mode.HOTKEY ? 1 : Math.min(keys, expectedSum);
        boolean exact = actualSum == expectedSum && mapSize == expectedKeys;
        double millis = round1(elapsedNanos / 1_000_000.0);
        double opsPerMillis = round1(actualSum / Math.max(elapsedNanos / 1_000_000.0, 0.001));

        List<Long> distribution = new ArrayList<>(shardCount);
        for (long count : shardOps) {
            distribution.add(count);
        }
        return new ModeResult(mode.label, shardCount, maxConcurrent.get(), millis,
                opsPerMillis, expectedSum, actualSum, mapSize, exact,
                List.copyOf(distribution));
    }

    /**
     * 忙等指定微秒，模拟锁内的业务处理。
     *
     * <p>空转期间线程处于 RUNNABLE，会真实占用 CPU；这正是实验想展示的“锁内耗时”。
     * 生产代码不应该这样写，真实业务应尽量缩短临界区。</p>
     */
    private static void spinMicros(int micros) {
        long deadline = System.nanoTime() + TimeUnit.MICROSECONDS.toNanos(micros);
        while (System.nanoTime() < deadline) {
            // 故意空转：sleep 的最小粒度通常是毫秒级，无法表达 100µs 的临界区
        }
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double ratio(double part, double total) {
        return Math.round(part / Math.max(total, 0.001) * 100.0) / 100.0;
    }

    private static void await(CountDownLatch latch, long millis) {
        try {
            latch.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 三种模式的名字直接出现在接口返回里，和文档保持一致。 */
    private enum Mode {
        GLOBAL("global"),
        SHARDED("sharded"),
        HOTKEY("hotkey");

        private final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    /**
     * 一种模式的运行结果。
     *
     * @param mode                 global / sharded / hotkey
     * @param shards               实际分片数；global 固定为 1
     * @param maxConcurrent        临界区内观察到的最大并发线程数；全局锁应为 1
     * @param elapsedMillis        本模式耗时（毫秒）
     * @param opsPerMillis         操作/毫秒
     * @param expectedSum          理论总和 threads × operations
     * @param actualSum            实际总和
     * @param mapSize              最终 key 数量（所有分片表相加）
     * @param exact                总和与 key 数是否都符合预期
     * @param shardDistribution    每个分片处理的操作数
     */
    public record ModeResult(String mode, int shards, int maxConcurrent,
                             double elapsedMillis, double opsPerMillis,
                             int expectedSum, int actualSum, int mapSize, boolean exact,
                             List<Long> shardDistribution) {
    }

    /**
     * 三种分片策略的对比结果。
     *
     * @param threads    线程数
     * @param keys       key 总数
     * @param operations 每线程操作次数
     * @param shards     分片数
     * @param workMicros 锁内忙等微秒数
     * @param modes      global / sharded / hotkey 的明细
     * @param conclusion 本次实验结论
     * @param note       统计口径
     */
    public record Result(int threads, int keys, int operations, int shards, int workMicros,
                         List<ModeResult> modes, String conclusion, String note) {
    }
}
