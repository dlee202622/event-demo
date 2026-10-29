package com.example.demo.concurrency.support;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.example.demo.concurrency.support.Observations.ThreadSnapshot;

/**
 * 用 JDK 自带的 {@link ThreadMXBean} 观测锁：线程状态、等待的锁、锁的持有者、死锁。
 *
 * <p>相比 JOL 看对象头，MXBean 的优点是零额外依赖、跨 JVM 参数稳定；
 * 缺点是它只能看到“JVM 愿意暴露的摘要”，看不到 mark word 的具体位。
 * 对教学来说，能回答“谁在等谁”比“对象头第几位是 1”更有用。</p>
 */
public final class ThreadSampler {

    /** 全局唯一的 MXBean 代理，线程安全，可以直接静态持有。 */
    private static final ThreadMXBean MX = ManagementFactory.getThreadMXBean();

    private ThreadSampler() {
    }

    /**
     * 采样一个线程的瞬时状态。
     *
     * <p>线程已经结束时 {@code getThreadInfo} 返回 null，这里统一映射成
     * {@code TERMINATED}，避免调用方到处判空。</p>
     *
     * @param thread 目标线程
     * @return 快照；锁相关字段在无锁时可能为 null
     */
    public static ThreadSnapshot sample(Thread thread) {
        // getThreadInfo(long) 单参重载默认 maxDepth=0，栈帧永远为空；
        // 这里用带布尔参数的批量重载（maxDepth 取 Integer.MAX_VALUE），
        // 同时抓取监视器与 ReentrantLock 的持有者信息，topFrame 才有内容
        ThreadInfo[] found = MX.getThreadInfo(new long[]{thread.threadId()}, true, true);
        ThreadInfo info = found.length == 0 ? null : found[0];
        if (info == null) {
            return new ThreadSnapshot(thread.getName(), thread.threadId(), "TERMINATED",
                    null, null, -1, null);
        }
        StackTraceElement[] stack = info.getStackTrace();
        String topFrame = stack.length == 0 ? null : stack[0].toString();
        return new ThreadSnapshot(
                info.getThreadName(),
                info.getThreadId(),
                info.getThreadState().name(),
                info.getLockName(),
                info.getLockOwnerName(),
                info.getBlockedCount(),
                topFrame);
    }

    /**
     * 批量采样，保持传入顺序，方便和线程名一一对应。
     */
    public static List<ThreadSnapshot> sampleAll(Collection<Thread> threads) {
        List<ThreadSnapshot> snapshots = new ArrayList<>(threads.size());
        for (Thread thread : threads) {
            snapshots.add(sample(thread));
        }
        return snapshots;
    }

    /**
     * 找出“属于本次实验”的死锁线程。
     *
     * <p>{@code findDeadlockedThreads()} 返回的是整个 JVM 的死锁线程 id，
     * 如果其他测试或上一次实验残留了死锁线程，不筛选就会误报。这里只保留
     * 调用方传入的 worker id。</p>
     *
     * @param workerIds 本次实验创建的线程 id
     * @return 命中的死锁线程 id；没有命中返回空数组
     */
    public static long[] deadlockedAmong(Set<Long> workerIds) {
        long[] all = MX.findDeadlockedThreads();
        if (all == null) {
            return new long[0];
        }
        return Arrays.stream(all).filter(workerIds::contains).toArray();
    }

    /**
     * 读取死锁线程的详细信息（含锁对象、锁持有者和栈）。
     *
     * @param ids {@link #deadlockedAmong(Set)} 返回的线程 id
     * @return ThreadInfo 数组；id 对应线程已结束时可能含 null
     */
    public static ThreadInfo[] details(long[] ids) {
        // true/true：同时抓取对象监视器和 ownable synchronizer（ReentrantLock 属于后者）
        return MX.getThreadInfo(ids, true, true);
    }
}
