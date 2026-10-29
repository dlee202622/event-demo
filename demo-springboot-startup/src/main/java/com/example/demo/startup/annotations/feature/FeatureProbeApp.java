package com.example.demo.startup.annotations.feature;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * full 档子应用入口：用来在同一个 JVM 里另起一个 NONE 容器，
 * 让 {@code mode = "full"} 的导入结果可被单独测量。
 *
 * <p>定位：这是本模块对「{@code @SpringBootApplication} 其实是三个注解」最直白的演示——
 * 这里<b>故意不用</b>那个三合一注解，而是手写
 * {@link SpringBootConfiguration @SpringBootConfiguration}
 * + {@link EnableAutoConfiguration @EnableAutoConfiguration}，
 * 少了 {@code @ComponentScan}。于是这个子容器里：
 * 没有任何扫描产物、只有自动配置 + 一个被 {@code @EnableStartupFeature} 导入的配置类。
 * 接口返回的 Bean 定义数因此非常小且完全可解释。</p>
 *
 * <p>关键实现：{@code @EnableStartupFeature(mode = "full")} 标在本类上，
 * 所以选择器收到的 {@code importingClass} 是本类全限定名，
 * 与主应用里 {@link FeatureEnableDemoConfig} 触发的那次形成同一条链的两个样本。</p>
 *
 * <p>坑：本类被主应用的组件扫描扫到会引入第二个 {@code StartupFeatureApi} 与属性 Bean，
 * 因此已加入 {@code StartupApplication} 的 {@code excludeFilters}；
 * 这正是 {@code excludeFilters} 在本模块的第二处真实用途（第一处见场景 2/3/4 的三个探针类）。</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableStartupFeature(mode = "full")
public class FeatureProbeApp {
}
