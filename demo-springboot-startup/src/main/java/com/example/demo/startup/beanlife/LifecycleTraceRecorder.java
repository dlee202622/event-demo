package com.example.demo.startup.beanlife;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 场景 4 的站点记录仪：Bean 生命周期每一步都在这里落一条有序记录。
 *
 * <p>定位：BeanPostProcessor / Aware 回调等扩展点不是 Spring Bean，
 * 无法互相注入协调，用静态收集器把散落在十几个类、十几个时机的“到达证据”
 * 串成一条可返回给接口的时间线。</p>
 *
 * <p>关键实现：每条记录带 instanceKey（“LIFECYCLE-DEMO#3”）。
 * 每次验证都会新建一个短命容器，同一种 Bean 会被创建多次；
 * 没有实例标记就无法区分“主应用里那个常驻 demo Bean”和“刚跑完实验的子容器 Bean”。</p>
 *
 * <p>坑：记录以容器 close() 为界——销毁站点在关闭钩子线程上写入，
 * 若你在别的线程并发调用 trace() 可能看到中间状态；教学场景是单线程串行，可接受。</p>
 */
public final class LifecycleTraceRecorder {

    /**
     * 一条生命周期到达记录。
     *
     * @param seq        全局序号
     * @param stage      站点名，如 "1-constructor"、"8-bpp-after"
     * @param detail     该站点此刻观察到的事实（参数注入值、代理类等）
     * @param threadName 执行线程，全程应为 main
     * @param instanceKey 属于哪个 Bean 实例
     */
    public record StageEntry(int seq, String stage, String detail, String threadName, String instanceKey) {
    }

    private static final List<StageEntry> ENTRIES = Collections.synchronizedList(new ArrayList<>());

    private static final AtomicInteger INSTANCE_COUNTER = new AtomicInteger();

    /**
     * 当前“实验批次”标记：由 LifecycleBeanLifeService 在启动子容器前设置。
     * 约定同一 JVM 内实验串行执行（synchronized trace 方法保证），
     * 所以构造时刻读到的 marker 必然属于正在创建该 Bean 的那次实验。
     */
    private static volatile String currentMarker = "";

    /** 设定标记并返回它（调用方自己也要拿去做过滤键） */
    public static synchronized String beginRun(String marker) {
        currentMarker = marker;
        return marker;
    }

    /** 每个新实例分配唯一 key：marker#全局序号 */
    public static String newInstanceKey() {
        return currentMarker + "#" + INSTANCE_COUNTER.incrementAndGet();
    }

    /**
     * 记录一个站点的到达。
     *
     * @param instanceKey 由 {@link #newInstanceKey()} 在构造器里生成，全实例透传
     * @param stage       站点编号 + 名称（编号用于断言顺序，不依赖字符串排序）
     * @param detail      现场事实描述
     */
    public static void record(String instanceKey, String stage, String detail) {
        synchronized (ENTRIES) {
            ENTRIES.add(new StageEntry(ENTRIES.size() + 1, stage, detail,
                    Thread.currentThread().getName(), instanceKey));
        }
    }

    /**
     * 取某个实例的完整轨迹，按 seq 升序。
     *
     * @param instanceKey 目标实例
     * @return 命中记录；实例还没创建完时列表可能只有前几站
     */
    public static List<StageEntry> traceOf(String instanceKey) {
        synchronized (ENTRIES) {
            return ENTRIES.stream().filter(e -> e.instanceKey().equals(instanceKey)).toList();
        }
    }

    /** 全部记录（含所有实例），给 /beanlife/trace 的全量视图用 */
    public static List<StageEntry> all() {
        synchronized (ENTRIES) {
            return List.copyOf(ENTRIES);
        }
    }

    /** 仅测试用 */
    public static void reset() {
        ENTRIES.clear();
        INSTANCE_COUNTER.set(0);
    }

    private LifecycleTraceRecorder() {
    }
}
