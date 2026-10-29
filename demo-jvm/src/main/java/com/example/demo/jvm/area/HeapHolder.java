package com.example.demo.jvm.area;

import com.example.demo.jvm.support.Units;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 堆内存的手动持有 / 释放，用来把「堆涨了多少」变成一次请求就能看见的现象。
 *
 * <p>定位：读者在 {@code /api/jvm/areas} 看到堆是静态数字，很难建立
 * 「分配 → 堆上升 → GC → 下降」的因果。这个服务提供受控的分配按钮，
 * 并强制保留引用，让「为什么这次没被回收」有明确答案。</p>
 *
 * <p>关键实现：用 {@code byte[]} 而不是对象列表，因为 byte[] 大小就是请求里的 MB 数，
 * 不需要读者再算对象头和字段对齐；数组直接进老年代或大对象区，观察结果更干净。</p>
 *
 * <p>坑与保护：单次上限由 {@code demo.jvm.max-allocate-mb} 限制（默认 64 MB）。
 * 观测进程一旦被自己的演示接口打爆，就得重启才能继续看别的场景，
 * 所以宁可拒绝请求，也不让演示把主角杀掉。</p>
 */
@Service
public class HeapHolder {

    private final Deque<byte[]> retained = new ArrayDeque<>();
    private final int maxSingleMb;

    /** 累计保留的字节数，用 volatile 保证 GC 前后对比时读到的是最新值。 */
    private volatile long retainedBytes;

    /**
     * @param maxSingleMb 单次分配上限，来自 {@code demo.jvm.max-allocate-mb}（默认 64）
     *                    ——故意留一个「明显小于 -Xmx」的闸：演示接口可以被连点刷新，
     *                    没有闸的话多点几次观测进程自己就 OOM 了，
     *                    而读者真正该看的场景 3~6 还在后面
     */
    public HeapHolder(@Value("${demo.jvm.max-allocate-mb:64}") int maxSingleMb) {
        this.maxSingleMb = maxSingleMb;
    }

    /**
     * 分配一块内存。
     *
     * @param megabytes 申请大小，单位 MB；超过 {@code maxSingleMb} 直接被拒绝
     * @param retain    true 表示保留强引用（后续 GC 回收不掉，用来观察老年代上涨）；
     *                  false 表示不保留，下一次 Young GC 就会消失，用来观察「涨上去又掉回来」
     * @return 分配结果与当前持有总量
     * @throws IllegalArgumentException 大小非法或超过上限，或堆已不足以再放一块
     */
    public AllocationResult allocate(int megabytes, boolean retain) {
        if (megabytes <= 0) {
            throw new IllegalArgumentException("megabytes 必须为正数，收到 " + megabytes);
        }
        if (megabytes > maxSingleMb) {
            throw new IllegalArgumentException(
                    "单次最多 " + maxSingleMb + " MB（demo.jvm.max-allocate-mb 可调），收到 " + megabytes + " MB");
        }
        long heapFree = freeHeapBytes();
        // 先判断剩余空间：宁可返回 400 说明原因，也不要让观测进程当场 OOM 退出
        if (Units.mbToBytes(megabytes) > heapFree) {
            throw new IllegalArgumentException(String.format(
                    "堆剩余约 %s，无法再分配 %d MB。先调 /api/jvm/allocate/clear 释放，"
                            + "或重启时加大 -Xmx", Units.human(heapFree), megabytes));
        }
        byte[] block = new byte[(int) Units.mbToBytes(megabytes)];
        // 写一遍防止惰性分配：Linux 上未写入的页可能还没真正占用物理内存，堆指标会偏小
        for (int i = 0; i < block.length; i += 4096) {
            block[i] = 1;
        }
        if (retain) {
            synchronized (retained) {
                retained.addLast(block);
                retainedBytes += block.length;
            }
        }
        return new AllocationResult(
                megabytes,
                retain,
                heldBlocks(),
                Units.mb(retainedBytes),
                Units.mb(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()));
    }

    /**
     * 释放全部保留引用。
     *
     * <p>只做「清空引用」这一件事，不主动触发 GC：让读者自己调 {@code POST /api/jvm/gc}
     * 观察释放前后老年代的变化，因果链才完整。</p>
     *
     * @return 释放掉的块数与总量，以及此刻的堆已用（还没回收，所以不会立刻掉到最低）
     */
    public HeldStats clear() {
        int blocks;
        long bytes;
        synchronized (retained) {
            blocks = retained.size();
            bytes = retainedBytes;
            retained.clear();
            retainedBytes = 0;
        }
        return new HeldStats(blocks, bytes, Units.mb(bytes), currentHeapMb());
    }

    /** 当前持有的块数与总量，用于 {@code /api/jvm/hold}。 */
    public HeldStats stats() {
        synchronized (retained) {
            return new HeldStats(retained.size(), retainedBytes, Units.mb(retainedBytes), currentHeapMb());
        }
    }

    private static double currentHeapMb() {
        return Units.mb(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
    }

    /**
     * 当前保留的块数。
     *
     * <p>和 {@link #stats()} 分开是因为调用点只想知道个数（{@code allocate} 打完日志就用它），
     * 不必为了拿一个 int 去读一次堆用量——{@code getHeapMemoryUsage()} 不是免费的。</p>
     */
    public int heldBlocks() {
        synchronized (retained) {
            return retained.size();
        }
    }

    /**
     * 堆「还能放下多少」的估算：上限减去已用。
     *
     * <p>这个值偏保守（没有算 GC 能回收的垃圾），但对演示场景是对的：
     * 我们要保证请求进来的这块内存在不做 Full GC 的前提下也能装下。</p>
     */
    private long freeHeapBytes() {
        var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long max = heap.getMax();
        // max == -1 表示堆无硬上限（受物理内存约束），此时不做前置校验
        return max < 0 ? Long.MAX_VALUE : Math.max(0, max - heap.getUsed());
    }

    /**
     * @param heldBlocks 涉及的块数：{@code /hold} 是当前持有数，{@code /allocate/clear} 是刚释放的块数
     * @param heldBytes  对应的字节数
     * @param heldMb     换算成 MB，省得读者自己除
     * @param heapUsedMb 读取时刻的堆已用 MB
     */
    public record HeldStats(int heldBlocks, long heldBytes, double heldMb, double heapUsedMb) {
    }

    /**
     * @param megabytes    本次申请大小 MB
     * @param retain       是否保留强引用
     * @param heldBlocks   当前保留的块数
     * @param heldMb       当前保留总量 MB
     * @param heapUsedMb   调用后立刻读到的堆已用 MB（Young GC 可能还没发生，故只作参考）
     */
    public record AllocationResult(int megabytes, boolean retain, int heldBlocks,
                                   double heldMb, double heapUsedMb) {
    }
}
