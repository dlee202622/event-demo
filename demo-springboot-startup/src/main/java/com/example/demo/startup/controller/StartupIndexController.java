package com.example.demo.startup.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模块首页：列出六个场景的入口命令，接口本身即“文档索引”。
 *
 * <p>定位：本模块的接口分散在四个控制器里，且部分场景需要重启进程才能观测（场景 5）。
 * 首页把“现在就能点的接口”和“必须重启才能看的实验”分开列，避免读者对着
 * 一个永远返回 200 的接口找失败输出。</p>
 *
 * <p>关键实现：场景 0 排在最前但不改写 1~5 的编号——模块里大量注释引用了
 * “场景 2/3/5”，编号是文档的一部分，新增内容用“0”而不是重排。</p>
 */
@RestController
@RequestMapping("/api/startup")
public class StartupIndexController {

    /**
     * 真实监听端口。这里刻意注入 ${server.port} 而不是写死 9006：
     * 用 SERVER_PORT=9014 这类临时端口做验证时，首页回显的端口才和实际一致，
     * 否则读者会以为接口串了别的环境。
     */
    @Value("${server.port}")
    private int serverPort;

    /**
     * 六个场景的入口清单。
     *
     * @return scenarios 每项含 id / 标题 / 怎么触发 / 看什么
     */
    @GetMapping
    public Map<String, Object> index() {
        return Map.of(
                "module", "demo-springboot-startup",
                "port", serverPort,
                "scenarios", List.of(
                        Map.of("id", 0, "title", "注解与类（起点）",
                                "trigger", "GET /api/startup/annotations → /annotations/tree → /conditional → /spi"
                                        + " → /provenance → /proxymethods → /feature",
                                "observe", "每个注解点亮哪个类、@SpringBootApplication 的元注解树、"
                                        + "条件注解家族、.imports 与 spring.factories 的分工、"
                                        + "Bean 定义来源、proxyBeanMethods 两档实测差值"),
                        Map.of("id", 1, "title", "启动事件时间线",
                                "trigger", "GET /api/startup/timeline（数据在启动日志里已打好，接口只做回看）",
                                "observe", "7 个 RunListener 阶段 + 3 个容器事件的顺序与耗时"),
                        Map.of("id", 2, "title", "启动模式对比",
                                "trigger", "GET /api/startup/modes/compare；端口冲突看 GET /api/startup/modes/port-in-use",
                                "observe", "容器实现类、Bean 定义数、WebServer 类型与端口"),
                        Map.of("id", 3, "title", "自动配置揭秘",
                                "trigger", "GET /api/startup/autoconfig → /autoconfig/search?contains=FeatureColor → /autoconfig/exclude-demo",
                                "observe", "条件命中/落选原因；排除 DispatcherServletAutoConfiguration 的真实后果"
                                        + "（实测是启动失败，不是想象中的干净 404——自动配置之间有依赖）"),
                        Map.of("id", 4, "title", "Bean 生命周期",
                                "trigger", "GET /api/startup/beanlife/trace",
                                "observe", "从 constructor 到 destroy-method 的有序站点，含 BPP 换代理现场"),
                        Map.of("id", 5, "title", "启动失败双结局（必须重启进程）",
                                "trigger", "DEMO_FAIL_MODE=analyzer 与 DEMO_FAIL_MODE=raw 各启动一次，对比控制台输出",
                                "observe", "友好报告 DESCRIPTION/ACTION 三五行 vs 满屏堆栈")),
                "note", "场景 5 无法用接口触发：启动失败的应用根本没有接口。");
    }
}
