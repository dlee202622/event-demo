package com.example.demo.startup.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.startup.beanlife.LifecycleBeanLifeService;
import com.example.demo.startup.beanlife.LifecycleTraceRecorder;
import com.example.demo.startup.timeline.StartupTimelineCollector;
import com.example.demo.startup.timeline.StartupTimelineCollector.TimelineEntry;

/**
 * 场景 1（启动时间线）与场景 4（Bean 生命周期）的读接口。
 *
 * <p>定位：这两个场景的主证据在启动日志里（进程内一次性发生），
 * 接口只负责“把已经记录下来的东西再结构化返回一次”，方便对照与截图。</p>
 *
 * <p>坑：{@code /timeline} 的耗时基准是进程内第一条记录（starting），
 * 不含 JVM 启动与类加载；想量那段耗时请用 {@code java -verbose:gc} 之外的手段。</p>
 */
@RestController
@RequestMapping("/api/startup")
public class TimelineController {

    private final LifecycleBeanLifeService lifecycleService;

    public TimelineController(LifecycleBeanLifeService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    /**
     * 启动时间线：RunListener 的 7 个阶段 + 容器内 3 个事件，按真实到达顺序排列。
     *
     * @return 每条含 seq / source / phase / sinceLastMs / sinceStartMs / threadName / detail
     */
    @GetMapping("/timeline")
    public List<TimelineEntry> timeline() {
        return StartupTimelineCollector.entries();
    }

    /**
     * 跑一次完整的 Bean 生命周期实验（独立 NONE 子容器，用完即关）。
     *
     * @return 该实例从 constructor 到 destroy-method 的全部站点，按 seq 升序
     */
    @GetMapping("/beanlife/trace")
    public LifecycleBeanLifeService.LifecycleTrace beanLifeTrace() {
        return lifecycleService.trace();
    }

    /**
     * 主应用常驻演示 Bean 的轨迹（不含销毁站点：进程还活着）。
     *
     * @return 实例标记为 "#1" 的站点列表；空列表说明常驻实例未被创建过（懒加载场景）
     */
    @GetMapping("/beanlife/resident")
    public List<LifecycleTraceRecorder.StageEntry> resident() {
        return lifecycleService.residentTrace();
    }
}
