package com.example.demo.startup;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import com.example.demo.startup.annotations.feature.FeatureProbeApp;
import com.example.demo.startup.annotations.feature.StartupFeatureBasicConfig;
import com.example.demo.startup.annotations.feature.StartupFeatureFullConfig;
import com.example.demo.startup.autoconfig.probe.ProbeApp;
import com.example.demo.startup.beanlife.LifecycleProbeApp;
import com.example.demo.startup.modes.ModeProbeApp;

/**
 * demo-springboot-startup 入口：一个“被解剖的青蛙”式应用——
 * 业务接口全部服务于观测 Spring Boot 自身的启动过程（场景 1~5，见 README）。
 *
 * <p>定位：本 JVM 内除主应用外，各接口还会临时创建子 SpringApplication
 * （NONE/REACTIVE 对比、Bean 生命周期、启动失败演示），
 * 因此启动日志里会出现多次 “Starting/Started ...App”，
 * 时间线归属规则见 StartupTimelineRunListener 类注释。</p>
 *
 * <p>关键实现：excludeFilters 干着两件不同的活。
 * ① 剔除四个「子应用入口」（{@code ModeProbeApp} 等）。它们带
 * {@code @SpringBootApplication} 或 {@code @SpringBootConfiguration}（后者是前者的组成注解之一），
 * 若被主应用扫到，会用自己的包路径再扫一遍、并把自动配置再导入一次，
 * 主容器里就凭空多出第二个业务 Bean；
 * ② 剔除 {@code StartupFeatureBasicConfig} 与 {@code StartupFeatureFullConfig}——
 * 它们<b>只应由 {@code @EnableStartupFeature} 的选择器导入</b>。实测过反面教材：
 * 一旦让它们被扫描，两档配置会同时进主容器，
 * {@code getBean(StartupFeatureApi.class)} 直接抛 NoUniqueBeanDefinition，
 * 而 {@code /annotations/provenance} 里它们的来源也会从「@Import 导入」退化成「组件扫描」，
 * 场景 0 最想证明的那条链就消失了。</p>
 *
 * <p>坑：类路径同时有 webmvc(Tomcat) 与 webflux(Netty)（场景 2 需要），
 * 自动推断结果取决于类路径与 Deducer 顺序，主应用因此显式钉死 SERVLET。</p>
 */
@SpringBootApplication
// 显式声明 @ComponentScan 会覆盖 @SpringBootApplication 元注解里的那份，
// 因此必须把 Boot 自带的两个 CUSTOM 过滤器一并补齐（官方文档要求的写法）
@ComponentScan(excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                ModeProbeApp.class, LifecycleProbeApp.class, ProbeApp.class, FeatureProbeApp.class,
                StartupFeatureBasicConfig.class, StartupFeatureFullConfig.class})})
public class StartupApplication {

    public static void main(String[] args) {
        // 不用便利方法 SpringApplication.run(Class, args)，
        // 因为要演示“new SpringApplication + setter 才是完整控制面”这条路
        SpringApplication application = new SpringApplication(StartupApplication.class);
        // 显式指定启动模式：等价于配置项 spring.main.web-application-type=servlet
        application.setWebApplicationType(WebApplicationType.SERVLET);
        application.run(args);
    }
}
