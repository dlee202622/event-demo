package com.example.demo.jvm.gc;

import com.example.demo.jvm.support.Units;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayDeque;
import java.util.Arrays;

/**
 * 一段可重复、可量化、四个收集器都能跑的同一种负载，用来做 GC 横向对比。
 *
 * <p>为什么需要单独一个负载类：对比 GC 的前提是「工作量完全相同」，
 * 否则慢的那个到底是收集器差还是活儿多，读者无从判断。
 * 所以这里把分配行为固定成一个纯函数：{@code rounds × churnMb} 的短命对象 +
 * 一个恒定 {@code liveMb} 的常驻存活集，参数写进报告，换个收集器只换 JVM 参数。</p>
 *
 * <p>两个设计细节（都是踩过坑的）：
 * <ul>
 *   <li><strong>对象必须真的分配</strong>：热点循环里 {@code new byte[32768]} 如果内容没人读、
 *       引用也没留住，JIT 的逃逸分析+标量替换有可能把它整个消掉，GC 次数直接归零。
 *       所以每个数组既塞进 {@link ArrayDeque}（引用逃逸）又读回一个字节算校验和；</li>
 *   <li><strong>填充用确定性伪随机</strong>：线性同余、种子固定，
 *       于是校验和在各收集器之间必须完全相等——这既是「工作量相同」的证据，
 *       也顺手验证了子 JVM 没跑偏。</li>
 * </ul></p>
 *
 * <p>存活集故意「每轮换掉一个」：这样会有一批对象反复熬过 Young GC，
 * 年龄递增后被晋升，场景5（{@code -Xlog:gc+age=trace}）用的就是同一套机制。</p>
 */
public final class AllocationWorkload {

    private AllocationWorkload() {
    }

    /**
     * @param wallClockMs   负载本身的挂钟时间（不含 JVM 启动），算吞吐率的分母
     * @param allocatedMb   累计分配量 = 短命对象 + 存活集，约等于「GC 一共要搬运多少」
     * @param objects       分配出去的对象个数
     * @param checksum      确定性校验和；同一参数下四种收集器必须一模一样
     * @param gcCount       负载期间所有收集器次数增量（进程内视角，用来和日志解析对账）
     * @param gcTimeMs      负载期间所有收集器累计耗时增量，单位毫秒
     * @param heapUsedStartMb 负载开始时的堆使用量，采样值，受 GC 时机影响
     * @param heapUsedEndMb   负载结束时的堆使用量，同上
     */
    public record Stats(long wallClockMs,
                        double allocatedMb,
                        long objects,
                        long checksum,
                        long gcCount,
                        long gcTimeMs,
                        double heapUsedStartMb,
                        double heapUsedEndMb) {
    }

    /**
     * 跑一轮完整负载。
     *
     * @param rounds   轮数；每轮分配 {@code churnMb} 的短命对象并换掉存活集里一个元素
     * @param churnMb  每轮丢弃的短命对象总量，单位 MB；决定 Young GC 频率
     * @param liveMb   常驻存活集大小，单位 MB；决定晋升压力和 Old 区占用
     * @param objectKb 单个对象大小，单位 KB。取 32 时远小于 G1 的 humongous 阈值
     *                 （region 大小的一半，默认 1 MB），保证所有收集器都按「普通年轻代对象」
     *                 处理，横向对比才公平
     * @param seed     填充数据的种子
     * @return 负载统计
     */
    public static Stats run(int rounds, int churnMb, int liveMb, int objectKb, long seed) {
        int objectBytes = objectKb * 1024;
        // 每轮对象数 = 目标字节数 / 单对象字节数，向下取整至少 1 个
        int perRound = Math.max(1, (churnMb * 1024 * 1024) / objectBytes);
        int liveCount = Math.max(1, (liveMb * 1024 * 1024) / objectBytes);

        long gcCountBefore = collectorTotals();
        long gcTimeBefore = collectorTimeMs();
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        double heapStart = Units.mb(memory.getHeapMemoryUsage().getUsed());

        long start = System.nanoTime();
        long checksum = 0;
        long objects = liveCount;

        // 存活集：容量恒定 liveCount，旧元素一被换掉就立刻成为垃圾，新元素则反复熬过 Young GC
        ArrayDeque<byte[]> live = new ArrayDeque<>(liveCount);
        long state = seed | 1L;
        for (int i = 0; i < liveCount; i++) {
            state = next(state);
            live.add(fill(new byte[objectBytes], state));
        }

        for (int round = 0; round < rounds; round++) {
            ArrayDeque<byte[]> temp = new ArrayDeque<>(perRound);
            for (int i = 0; i < perRound; i++) {
                state = next(state);
                byte[] data = fill(new byte[objectBytes], state);
                temp.add(data);
                objects++;
                // 读一个字节：内容被观察过，逃逸分析就不会再把整个数组化掉
                checksum += data[round % objectBytes];
            }
            // temp 离开作用域后整批成为垃圾，下一轮的分配把它们挤出 Eden
            live.poll();
            state = next(state);
            byte[] survivor = fill(new byte[objectBytes], state);
            live.add(survivor);
            objects++;
            checksum += survivor[0];
        }

        long wallClockMs = (System.nanoTime() - start) / 1_000_000;
        // 存活集到最后仍被引用：它必须真的活着，否则"晋升"就无从谈起
        checksum += live.size();

        return new Stats(wallClockMs,
                rounds * (double) churnMb + liveMb,
                objects,
                checksum,
                collectorTotals() - gcCountBefore,
                collectorTimeMs() - gcTimeBefore,
                heapStart,
                Units.mb(memory.getHeapMemoryUsage().getUsed()));
    }

    /**
     * 用固定步长把数组填满。{@code Arrays.fill} 比逐个下标写快得多，
     * 不会让负载被计算瓶颈掩盖——我们要测的是分配与回收，不是 CPU。
     */
    private static byte[] fill(byte[] target, long seed) {
        Arrays.fill(target, (byte) (seed >> 24));
        return target;
    }

    /** 线性同余：只取高位做填充值，跨 JDK 版本结果一致。 */
    private static long next(long state) {
        return state * 6364136223846793005L + 1442695040888963407L;
    }

    /**
     * 进程内 GC 次数：把所有 {@link GarbageCollectorMXBean} 加起来。
     *
     * <p>坑：不能只取第一个。G1 注册了 Young/Concurrent/Old 三个 Bean，
     * ZGC 分代有四个，Serial 有两个，只数一个会得到莫名其妙的 0。</p>
     */
    private static long collectorTotals() {
        long total = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            // 未启用的收集器返回 -1（表示不支持该操作），必须当 0 处理
            total += Math.max(0, bean.getCollectionCount());
        }
        return total;
    }

    private static long collectorTimeMs() {
        long total = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, bean.getCollectionTime());
        }
        return total;
    }
}
