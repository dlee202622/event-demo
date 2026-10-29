package com.example.demo.concurrency.support;

/**
 * 各场景共用的观测记录。
 *
 * <p>所有时间点都是相对“本次实验开始”的毫秒数，而不是系统时钟：
 * 这样返回体可以直接横向对比，也不会受机器时间调整影响。</p>
 */
public final class Observations {

    private Observations() {
    }

    /**
     * 一次临界区的进入 / 退出记录。
     *
     * @param thread        线程名，用于对照谁先谁后
     * @param enterAtMillis 进入临界区的相对毫秒数
     * @param exitAtMillis  离开临界区的相对毫秒数
     */
    public record Span(String thread, long enterAtMillis, long exitAtMillis) {

        /**
         * 临界区停留时长（毫秒）；并发场景里用它判断两段区间是否重叠。
         */
        public long durationMillis() {
            return exitAtMillis - enterAtMillis;
        }
    }

    /**
     * 某一瞬间的线程状态快照，字段直接取自 {@code ThreadMXBean}。
     *
     * @param threadName   线程名
     * @param threadId     JVM 内线程 id
     * @param state        RUNNABLE / BLOCKED / WAITING / TIMED_WAITING / TERMINATED
     * @param waitingOn    正在等待的锁对象（monitor 或 synchronizer 的 toString）
     * @param lockOwner    该锁当前持有者的线程名；没有则 null
     * @param blockedCount 累计进入 BLOCKED 的次数（JVM 统计值，不是当前阻塞数）
     * @param topFrame     采样时栈顶方法，便于确认线程卡在哪一行
     */
    public record ThreadSnapshot(String threadName, long threadId, String state,
                                 String waitingOn, String lockOwner, long blockedCount,
                                 String topFrame) {
    }

    /**
     * 时间线事件：把“谁在什么时候做了什么”按发生顺序记录下来。
     *
     * @param atMillis 相对开始时刻的毫秒数
     * @param thread   线程名
     * @param action   动作，例如 lock / blocked / interrupt / unlock
     * @param note     补充信息，例如 holdCount、异常类型
     */
    public record TimelineEvent(long atMillis, String thread, String action, String note) {
    }
}
