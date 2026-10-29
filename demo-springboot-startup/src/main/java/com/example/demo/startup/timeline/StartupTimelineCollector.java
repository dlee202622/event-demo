package com.example.demo.startup.timeline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 场景 1 的时间线仓库：RunListener 与 ApplicationListener 都往这里写，
 * {@code GET /api/startup/timeline} 从这里读。
 *
 * <p>定位：RunListener（spring.factories，非 Bean）和事件监听器（容器 Bean）
 * 是两种不同生命周期的对象，无法互相注入，所以用静态收集器做桥梁。
 * 这是启动期埋点的常见妥协——容器还没建立时“Bean 单例”还不存在。</p>
 *
 * <p>坑：静态可变状态在多 SpringApplication 同 JVM 场景会互相污染，
 * 归属过滤在 {@link StartupTimelineRunListener#isMainApplication()} 完成。</p>
 */
public final class StartupTimelineCollector {

    private static final Logger log = LoggerFactory.getLogger("demo.startup.timeline");

    /**
     * 一条启动记录。
     *
     * @param seq            全局序号（从 1 开始）
     * @param source         RUN_LISTENER（容器前阶段）或 CONTEXT_EVENT（容器内事件）
     * @param phase          阶段名或事件类简名
     * @param sinceLastMs    距上一条记录的毫秒数，用来定位“哪一步最慢”
     * @param sinceStartMs   距进程内第一条记录的毫秒数
     * @param threadName     执行线程；启动主流程应全程 main，出现其他线程说明有异步初始化
     * @param detail         人类可读补充信息
     */
    public record TimelineEntry(int seq, String source, String phase, long sinceLastMs, long sinceStartMs,
            String threadName, String detail) {
    }

    /** synchronizedList + 显式 synchronized 追加，保证 delta 计算与列表快照一致 */
    private static final List<TimelineEntry> ENTRIES = Collections.synchronizedList(new ArrayList<>());

    private static long startNanos = -1;

    private static long lastNanos = -1;

    private StartupTimelineCollector() {
    }

    /**
     * 记录一个启动阶段。耗时不需要调用方传入：
     * 第一条记录把 startNanos 定为自己（进程内最早观测点），之后每条自动算差值。
     *
     * @param source 来源标记，见 {@link TimelineEntry#source()}
     * @param phase  阶段名，如 starting / ApplicationReadyEvent
     * @param detail 补充说明，会原样出现在接口返回和日志里
     */
    public static synchronized void record(String source, String phase, String detail) {
        long now = System.nanoTime();
        if (startNanos < 0) {
            startNanos = now;
        }
        long sinceLast = (lastNanos < 0) ? 0 : (now - lastNanos) / 1_000_000;
        long sinceStart = (now - startNanos) / 1_000_000;
        lastNanos = now;
        ENTRIES.add(new TimelineEntry(ENTRIES.size() + 1, source, phase, sinceLast, sinceStart,
                Thread.currentThread().getName(), detail));
    }

    /** RunListener 专用简写，source 固定为 RUN_LISTENER */
    static void recordPhase(String phase, long ignoredMs, String detail) {
        record("RUN_LISTENER", phase, detail);
    }

    /** 事件监听器专用简写，source 固定为 CONTEXT_EVENT */
    public static void recordEvent(String phase, String detail) {
        record("CONTEXT_EVENT", phase, detail);
    }

    /**
     * 打一条阶段日志。格式固定为 `阶段名 - 距上一条耗时ms | 距启动耗时ms | 线程`，
     * README/教程里直接复制这份输出作为实测证据。
     */
    public static void log(String phase, String detail) {
        TimelineEntry last;
        synchronized (ENTRIES) {
            last = ENTRIES.isEmpty() ? null : ENTRIES.get(ENTRIES.size() - 1);
        }
        if (last == null || !last.phase().equals(phase)) {
            // 日志在 record 之后调用，正常情况下能取到本条；取不到就不打耗时避免误导
            log.info("[{}] {}（{}）", phase, detail, Thread.currentThread().getName());
            return;
        }
        log.info("[{}] {} | +{}ms | 距启动 {}ms | 线程 {}",
                phase, detail, last.sinceLastMs(), last.sinceStartMs(), last.threadName());
    }

    /** 时间线快照，seq 单调递增；接口返回前端的结构与此完全一致 */
    public static List<TimelineEntry> entries() {
        synchronized (ENTRIES) {
            return List.copyOf(ENTRIES);
        }
    }

    /** 仅测试用：清空重来，避免单测之间互相污染 */
    static synchronized void reset() {
        ENTRIES.clear();
        startNanos = -1;
        lastNanos = -1;
    }
}
