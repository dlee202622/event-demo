package com.example.demo.startup.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.startup.autoconfig.AutoConfigService;
import com.example.demo.startup.modes.ModeSnapshot;
import com.example.demo.startup.modes.StartupModesService;

/**
 * 场景 2（启动模式）与场景 3（自动配置）的读接口。
 *
 * <p>定位：接口内部会临时创建并关闭子 Spring 应用（端口一律 0），
 * 因此耗时以秒计，属正常现象。</p>
 *
 * <p>坑：并发打 {@code /modes/compare} 与 {@code /autoconfig/exclude-demo}
 * 会同时拉起多个 Tomcat/Netty 实例，内存和线程数会短时上涨；
 * 教学演示请按顺序逐个调用。</p>
 */
@RestController
@RequestMapping("/api/startup")
public class StartupProbeController {

    private final StartupModesService modesService;

    private final AutoConfigService autoConfigService;

    public StartupProbeController(StartupModesService modesService, AutoConfigService autoConfigService) {
        this.modesService = modesService;
        this.autoConfigService = autoConfigService;
    }

    /**
     * 三种启动模式 + 自动推断的并排对比（4 次子应用启动）。
     *
     * @return NONE / REACTIVE / SERVLET / AUTO-DEDUCED 四条快照；单条失败不影响其余
     */
    @GetMapping("/modes/compare")
    public List<ModeSnapshot> compareModes() {
        return modesService.compare();
    }

    /**
     * 端口冲突实验：先用临时 Socket 占住端口，再让子应用去绑同一个端口。
     *
     * @return FAILED 快照，failureMessage 内含 “already in use” 原文与异常链
     */
    @GetMapping("/modes/port-in-use")
    public ModeSnapshot portInUse() {
        return modesService.portInUse();
    }

    /**
     * 条件评估报告摘要：总量 + 本模块三个演示 Bean 的判定结果。
     *
     * @return available=false 只有一种成因：容器还没就绪（本接口只在主应用运行期可观察）；
     *         报告本身每次启动都会记录，不依赖 debug 开关
     */
    @GetMapping("/autoconfig")
    public AutoConfigService.ReportSummary autoConfigSummary() {
        return autoConfigService.summary();
    }

    /**
     * 条件报告检索：source / 条件类名 / 判定消息任一命中即返回。
     *
     * @param contains 实测有结果的关键词如 FeatureColor、DispatcherServlet、OnPropertyCondition；
     *                 DataSource 会返回空数组——该类不在本模块类路径上，连候选都不是，
     *                 「查不到」和「判定为 false」是两回事
     * @param limit    最多返回条数，默认 20，上限 200（防止一次拉爆响应）
     */
    @GetMapping("/autoconfig/search")
    public List<AutoConfigService.ConditionEntry> searchConditions(
            @RequestParam(defaultValue = "") String contains,
            @RequestParam(defaultValue = "20") int limit) {
        // clamp 到 [1,200]：limit<=0 会让结果空转，超大 limit 则把整个报告（上千条）灌进响应
        return autoConfigService.search(contains, Math.min(Math.max(limit, 1), 200));
    }

    /**
     * 自动配置类清单片段：证明“自动配置就是一批普通类 + 条件过滤”。
     *
     * @param contains 类名过滤关键词，留空返回全部内置样例
     */
    @GetMapping("/autoconfig/list")
    public List<AutoConfigService.AutoConfigEntry> listAutoConfigs(
            @RequestParam(defaultValue = "") String contains) {
        return autoConfigService.listAutoConfigs(contains);
    }

    /**
     * 反例实验：排除 {@code DispatcherServletAutoConfiguration} 前后各起一个子应用并实测状态码。
     *
     * @return baseline 200；excluded 实测为启动失败（连坐 ErrorMvcAutoConfiguration），
     *         结论字段会说明原因
     */
    @GetMapping("/autoconfig/exclude-demo")
    public AutoConfigService.ExcludeDemoResult excludeDemo() {
        return autoConfigService.excludeDemo();
    }
}
