package com.example.demo.startup.annotations.feature;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * full 档的产物：只有 {@code @EnableStartupFeature(mode = "full")} 才会被导入。
 *
 * <p>定位：多出来的不只是一个 Bean，而是<b>第二条注解链</b>——
 * {@code @EnableConfigurationProperties} 头上挂着
 * {@code @Import(EnableConfigurationPropertiesRegistrar.class)}（实测 javap 结果），
 * 由那个 Registrar 把 {@link StartupFeatureProperties} 注册成 Bean Definition
 * 并保证绑定后处理器在场。读者由此能理解：{@code @EnableXxx} 是可以层层嵌套的，
 * 一个注解点亮的是「一串」类。</p>
 *
 * <p>关键实现：{@code startupFeatureApiFull(StartupFeatureProperties props)} 用<b>参数注入</b>
 * 拿属性，而不是在方法体里调 {@code propertiesBean()}——参数注入是「容器负责装配」的正解，
 * 与 {@code proxyBeanMethods} 开关无关，两种模式下都成立。</p>
 *
 * <p>坑：{@code @EnableConfigurationProperties(StartupFeatureProperties.class)} 里不写类时，
 * 只注册绑定后处理器、不注册属性 Bean；此时 {@code @Bean} 方法要属性就会 {@code NoSuchBeanDefinitionException}。
 * 报错信息不会提到注解少写了参数，容易误诊成「yaml 没配」。</p>
 */
@Configuration
@EnableConfigurationProperties(StartupFeatureProperties.class)
public class StartupFeatureFullConfig {

    /** 属性由容器注入：names/retries 的实际值来自 yaml，命令行参数可覆盖（优先级见场景 3） */
    @Bean
    public StartupFeatureApi startupFeatureApiFull(StartupFeatureProperties properties) {
        List<String> names = properties.getNames() == null ? List.of() : properties.getNames();
        return new StartupFeatureApi("full", names, properties.getRetries());
    }
}
