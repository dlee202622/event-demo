package com.example.demo.startup.annotations.feature;

import org.springframework.context.annotation.Configuration;

/**
 * 主应用常驻的「启用点」：一个普通的 {@code @Configuration}，
 * 头上标了 {@link EnableStartupFeature}（默认 basic 档）。
 *
 * <p>定位：{@code @EnableXxx} 可以标在启动类上，也可以标在任意配置类上——
 * 差别只在「选择器收到的 {@code AnnotationMetadata} 是哪个类」。
 * 本类故意不标在 {@code StartupApplication} 上，而是单独占一个类，
 * 这样接口回显的 {@code importingClass} 就是这个类的全限定名，
 * 读者能亲眼看见「是谁启用的」这条信息从哪里来。</p>
 *
 * <p>关键实现：类体是空的。装配全部来自注解——这正是 {@code @Enable} 系注解的设计意图：
 * 让「用了什么能力」在类声明这一行就能读出来，而不是散落在 XML 或 Java Config 里。</p>
 *
 * <p>坑：它必须被组件扫描到（本类在 {@code com.example.demo.startup.annotations.feature}，
 * 属于主应用扫描范围）。如果把 {@code @EnableStartupFeature} 标在一个<b>没被注册</b>的类上，
 * 选择器根本不会被调用，且没有任何警告——这是「我加了 @Enable 怎么没反应」的头号原因。</p>
 */
@Configuration
@EnableStartupFeature
public class FeatureEnableDemoConfig {
}
