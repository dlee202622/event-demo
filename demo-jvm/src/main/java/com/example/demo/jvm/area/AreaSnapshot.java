package com.example.demo.jvm.area;

import java.util.List;
import java.util.Map;

/**
 * 运行时数据区快照的返回结构。
 *
 * <p>刻意分成两层：{@code areas} 是「教材上的六个区」，按规范讲定位和观测方式；
 * {@code pools} 是 HotSpot 真正暴露的内存池。两者不是一一对应的
 * （比如「程序计数器」在 MXBean 里根本没有对应项，「Metaspace」一个池对应教材上的方法区），
 * 分成两层正是为了让读者看见这个差异，而不是被一张表糊过去。</p>
 *
 * <p>{@code null} 约定：{@code maxMb} 为空表示「没有硬上限」或「当前实现给不出上限」，
 * 不是 0；{@code usedMb} 为空表示「这个区无法用内存池的方式观测」。
 * 序列化后读者看到 null，配合 {@code howToMeasure} 字段就知道该换什么工具。</p>
 */
public record AreaSnapshot(
        Process process,
        List<Area> areas,
        List<Pool> pools,
        List<Collector> collectors,
        DirectMemory directMemory,
        Threads threads,
        NativeMemory nativeMemory,
        Map<String, String> flags) {

    /** 进程身份信息；pid 用来把接口输出和 jcmd / jstat / jhsdb 命令串起来。 */
    public record Process(
            long pid,
            String javaVersion,
            String jvmName,
            String gcAlgorithm,
            long uptimeMs,
            int availableCpus,
            double physicalTotalMb,
            double physicalFreeMb,
            List<String> jvmArgs) {
    }

    /**
     * 教材意义上的一个数据区。
     *
     * @param scope       线程私有 / 线程共享 / 不属于规范
     * @param howToMeasure 这一区的用量到底怎么量出来（或为什么量不出来）
     */
    public record Area(
            String name,
            String scope,
            String stores,
            String howToMeasure,
            Double usedMb,
            Double committedMb,
            Double maxMb,
            String note) {
    }

    /** HotSpot 的内存池原样数据，用于和上面六个区对照。 */
    public record Pool(
            String name,
            String type,
            double usedMb,
            double committedMb,
            Double maxMb,
            double peakUsedMb,
            Double usedPercent,
            String belongsTo) {
    }

    /** 一个 GC 的累计统计；collectionCount 的增量就是「刚刚那次分配触发了几次回收」。 */
    public record Collector(
            String name,
            List<String> memoryPools,
            long collectionCount,
            long totalTimeMs,
            Double avgPauseMs,
            String phase) {
    }

    /** 直接内存：不属于 JVM 规范里的运行时数据区，但 OOM 常客。 */
    public record DirectMemory(
            long directBuffers,
            double directUsedMb,
            double directCapacityMb,
            double maxMb,
            long mappedBuffers,
            double mappedCapacityMb,
            String maxSource) {
    }

    /**
     * 线程与栈。栈区总量 = 单线程栈大小 × 活跃线程数，所以线程数一多，
     * 「栈」吃掉的本地内存会超过很多人的想象。
     */
    public record Threads(
            int threadCount,
            int daemonCount,
            int peakThreadCount,
            long totalStartedCount,
            double stackSizeKb,
            double estimatedStackTotalMb,
            Double nativeThreadCommittedMb,
            long deadlockCount) {
    }

    /** NMT（-XX:NativeMemoryTracking）汇总；未开启时整个字段为 null。 */
    public record NativeMemory(
            boolean enabled,
            double totalReservedMb,
            double totalCommittedMb,
            double threadMb,
            double classMb,
            double codeMb,
            double gcMb,
            double internalMb,
            double otherMb,
            String hint) {
    }
}
