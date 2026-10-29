package com.example.demo.startup.timeline;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 场景 1 的对照组：容器内 {@code @EventListener}，用来和 RunListener 做“能看到多早”的对比。
 *
 * <p>定位：RunListener 覆盖容器创建前的 4 个阶段；本类只能覆盖容器存在之后的事件。
 * 两者写入同一张时间线，接口返回里 source 字段一眼能看出分界。</p>
 *
 * <p>坑：{@code ApplicationStartingEvent} / {@code ApplicationEnvironmentPreparedEvent}
 * 发布时容器还不存在，{@code @Component} 监听器压根收不到——把它们“补”进时间线
 * 是做不到而不是没做。真正接收早期事件要用 {@code SpringApplication#addListeners}，
 * 且实例必须在 {@code run()} 之前挂上（此时它还不是 Bean）。</p>
 *
 * <p>替代方案：业务解耦优先用 ApplicationRunner/事件；只有启动观测、
 * 指标埋点这类横切需求才值得写 RunListener。</p>
 */
@Component
public class StartupEventListener {

    /**
     * refresh() 的最后一站：所有单例实例化完成后由容器广播。
     * 时间线上它出现在 RunListener 的 contextLoaded 之后、started 之前，
     * 这个顺序本身就是“refresh 发生在 started 回调前”的证据。
     */
    @EventListener
    public void onContextRefreshed(ContextRefreshedEvent event) {
        int beanCount = event.getApplicationContext().getBeanDefinitionCount();
        // 实测 6 → 174：contextLoaded 时只有主类与显式扫描到的定义，
        // 自动配置是 refresh 期间由 DeferredImportSelector 才解析注册的，
        // 所以两个数字必然不同——把“Bean 定义数”当成启动常量是常见误解
        StartupTimelineCollector.recordEvent("ContextRefreshedEvent",
                "容器 refresh 完成，Bean 定义 " + beanCount + " 个（contextLoaded 时只有 6 个，"
                        + "差值是 refresh 期间由自动配置导入的）");
        StartupTimelineCollector.log("ContextRefreshedEvent", "容器 refresh 完成");
    }

    /**
     * started 阶段的事件形态：EventPublishingRunListener 把它广播给所有 Bean。
     * Boot 的 Runner（ApplicationRunner/CommandLineRunner）在此之后执行。
     */
    @EventListener
    public void onApplicationStarted(org.springframework.boot.context.event.ApplicationStartedEvent event) {
        StartupTimelineCollector.recordEvent("ApplicationStartedEvent", "started 回调广播给容器 Bean");
        StartupTimelineCollector.log("ApplicationStartedEvent", "Runner 执行前");
    }

    /**
     * 最后一个事件，RunListener.ready() 同一时刻广播。
     * 收到它 = 可以接流量；@EventListener(ApplicationReadyEvent) 是挂载预热任务的标准位置。
     */
    @EventListener
    public void onApplicationReady(ApplicationReadyEvent event) {
        StartupTimelineCollector.recordEvent("ApplicationReadyEvent", "启动完成事件，进程开始对外服务");
        StartupTimelineCollector.log("ApplicationReadyEvent", "启动链路终点");
    }
}
