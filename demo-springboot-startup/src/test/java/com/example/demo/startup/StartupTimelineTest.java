package com.example.demo.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.demo.startup.timeline.StartupTimelineCollector;
import com.example.demo.startup.timeline.StartupTimelineCollector.TimelineEntry;

/**
 * 场景 1 断言：RunListener 的 7 个阶段必须按固定顺序出现，且全在主线程。
 *
 * <p>为什么值得测：这条顺序是 Spring Boot 启动流程的骨架，一旦某天升级变了，
 * 教程里的时间线就得同步改——这个测试就是那把“变更报警铃”。</p>
 */
@SpringBootTest
class StartupTimelineTest {

    /** 测试上下文也由 SpringApplication 创建，收集器里必然有完整阶段序列 */
    @Test
    void runListenerPhasesAppearInOrder() {
        List<String> phases = StartupTimelineCollector.entries().stream()
                .filter(e -> "RUN_LISTENER".equals(e.source()))
                .map(TimelineEntry::phase)
                .toList();
        assertThat(phases).containsExactly("starting", "environmentPrepared", "contextPrepared",
                "contextLoaded", "started", "ready");
    }

    /** 容器事件必须晚于 contextPrepared、早于或等于 started：证明“事件发布走的是同一批回调” */
    @Test
    void contextEventsInterleaveCorrectly() {
        List<TimelineEntry> all = StartupTimelineCollector.entries();
        int contextPrepared = indexOfPhase(all, "RUN_LISTENER", "contextPrepared");
        int refreshed = indexOfPhase(all, "CONTEXT_EVENT", "ContextRefreshedEvent");
        int started = indexOfPhase(all, "RUN_LISTENER", "started");
        assertThat(contextPrepared).isNotNegative();
        assertThat(refreshed).isGreaterThan(contextPrepared);
        assertThat(started).isGreaterThan(refreshed);
    }

    /** 启动主流程单线程：出现别的线程名说明有异步初始化混进来了 */
    @Test
    void startupRunsOnMainThread() {
        assertThat(StartupTimelineCollector.entries())
                .filteredOn(e -> e.phase().equals("starting") || e.phase().equals("ready"))
                .allSatisfy(e -> assertThat(e.threadName()).isEqualTo("main"));
    }

    private static int indexOfPhase(List<TimelineEntry> entries, String source, String phase) {
        for (int i = 0; i < entries.size(); i++) {
            TimelineEntry e = entries.get(i);
            if (e.source().equals(source) && e.phase().equals(phase)) {
                return i;
            }
        }
        return -1;
    }
}
