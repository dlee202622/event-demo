package com.example.demo.startup.timeline;

import java.time.Duration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 场景 1 的主角：用 {@link SpringApplicationRunListener} 给启动全过程装“黑匣子”。
 *
 * <p>定位：{@code SpringApplication.run()} 内部按固定顺序回调 7 个阶段方法
 * （starting → environmentPrepared → contextPrepared → contextLoaded →
 * started → ready → failed），本类在每个回调里记一行时间戳，
 * 于是能把“Boot 到底按什么顺序做了什么”变成可观测的数字。</p>
 *
 * <p>适用场景：排查“启动卡在哪个阶段”“某监听器为什么收不到早期事件”。
 * 官方自己的 {@code EventPublishingRunListener}（发布 Application*Event）
 * 和度量耗时的 {@code ApplicationStartup} 步骤，走的正是这同一套回调。</p>
 *
 * <p>关键实现：
 * ① 通过 {@code META-INF/spring.factories} 注册，构造器必须是
 * {@code (SpringApplication, String[])}——这是 {@code SpringFactoriesLoader}
 * 按参数类型注入的约定，签名不符会启动直接报错；
 * ② 本模块会在同一个 JVM 里反复创建子 SpringApplication（场景 2/4/5），
 * 用“第一个 starting 的实例即主应用”来归属事件，防止子应用污染时间线。</p>
 *
 * <p>坑：starting/environmentPrepared 发生时还没有容器，
 * {@code @Component} 形式的 {@code ApplicationListener} 根本收不到这两个事件，
 * 想观测最早期阶段只能用 RunListener——这正是本类存在的原因。</p>
 *
 * <p>替代方案：只想要事件顺序可监听 ApplicationXxxEvent；想要分段耗时火焰图
 * 可注入 {@code BufferingApplicationStartup}；生产环境想要“进程启动即计时”
 * 应该用 APM agent 而不是应用内埋点。</p>
 *
 * @see StartupTimelineCollector 记录存放处
 */
public class StartupTimelineRunListener implements SpringApplicationRunListener, Ordered {

    /**
     * 主应用判定：进程内第一个被 starting() 的 SpringApplication 即
     * {@code StartupApplication.main()} 创建的那个（HTTP 接口只能在之后才被调用）。
     * volatile：理论上并发创建子应用时可见性要有保证，实际由第一次赋值前的串行启动兜底。
     */
    private static volatile SpringApplication mainApplication;

    private final SpringApplication application;
    private final String[] args;

    /**
     * SpringFactoriesLoader 约定构造器：参数类型必须与
     * {@code ArgumentResolver.of(SpringApplication.class, this)} 和
     * {@code and(String[].class, args)} 完全对应，缺一个都实例化失败。
     */
    public StartupTimelineRunListener(SpringApplication application, String[] args) {
        this.application = application;
        this.args = args;
    }

    /**
     * 归属判定：只有主应用的回调才写入时间线。
     * 子应用（场景 2 的 NONE/REACTIVE、场景 5 的失败演示）走同一套
     * spring.factories，若不隔离，时间线会被多次“伪启动”截断。
     */
    private boolean isMainApplication() {
        if (mainApplication == null) {
            mainApplication = application;
        }
        return mainApplication == application;
    }

    @Override
    public void starting(ConfigurableBootstrapContext bootstrapContext) {
        // 阶段名沿用 Boot 内部 SpringApplicationRunListeners 的 step 名，
        // 便于和 actuator 的 startup endpoint 输出对照
        record("starting", "清理监听器、发布 ApplicationStartingEvent；此刻无容器、无 Environment");
    }

    @Override
    public void environmentPrepared(ConfigurableBootstrapContext bootstrapContext, ConfigurableEnvironment environment) {
        // 这是能读到配置的最早时机：yaml/命令行/环境变量已全部合入 environment，
        // 但 SpringApplication 自己的配置（如 spring.main.*）还没绑定完成
        record("environmentPrepared", "Environment 就绪（属性源 " + environment.getPropertySources().size()
                + " 个），命令行参数 " + args.length + " 个");
    }

    @Override
    public void contextPrepared(ConfigurableApplicationContext context) {
        // 容器实例已 new 出来但没有任何 Bean 定义：
        // 能看清“Web 应用类型决定了容器实现类”这件事
        record("contextPrepared", "容器已创建未加载：" + context.getClass().getSimpleName());
    }

    @Override
    public void contextLoaded(ConfigurableApplicationContext context) {
        // @ComponentScan / @Import 已解析成 Bean 定义，但还没有 refresh：
        // 实测这里只有 6 个，refresh 后变成 174 个——自动配置走的是 DeferredImportSelector，
        // 要等 refresh 期间的 invokeBeanFactoryPostProcessors 才展开，所以此刻的数量
        // 只包含主类与显式扫描到的定义，不含任何自动配置
        record("contextLoaded", "Bean 定义已加载，共 " + context.getBeanDefinitionCount()
                + " 个（自动配置尚未导入，refresh 后会跳升）");
    }

    @Override
    public void started(ConfigurableApplicationContext context, Duration timeTaken) {
        // refresh 完成 + Runner 执行完；timeTaken 是 Boot 自己算的“到 started 的总耗时”，
        // 直接采信它做对照，避免自己计时因 JVM 启动偏差失真
        record("started", "容器 refresh 完成，Boot 计时 " + millis(timeTaken) + "ms");
    }

    @Override
    public void ready(ConfigurableApplicationContext context, Duration timeTaken) {
        // 最后一个阶段：应用已可对外服务；SmartLifecycle 也已在本阶段前启动
        record("ready", "应用就绪，可接收请求，Boot 计时 " + millis(timeTaken) + "ms");
    }

    @Override
    public void failed(ConfigurableApplicationContext context, Throwable exception) {
        if (!isMainApplication()) {
            return;
        }
        StartupTimelineCollector.recordPhase("failed", 0, "启动中断：" + exception.getClass().getSimpleName());
        StartupTimelineCollector.log("failed", "启动中断：" + exception.getClass().getName());
    }

    private void record(String phase, String detail) {
        if (!isMainApplication()) {
            return;
        }
        StartupTimelineCollector.recordPhase(phase, 0, detail);
        StartupTimelineCollector.log(phase, detail);
    }

    private static String millis(Duration duration) {
        return duration == null ? "?" : String.valueOf(duration.toMillis());
    }

    /**
     * 默认实现顺序为最低优先级；这里不抢先，保证 Boot 自己的
     * EventPublishingRunListener 先发事件、我们后记录，顺序与官方一致。
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10;
    }
}
