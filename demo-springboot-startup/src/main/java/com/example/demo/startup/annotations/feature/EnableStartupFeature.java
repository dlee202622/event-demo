package com.example.demo.startup.annotations.feature;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Import;

/**
 * 自己写一个 {@code @EnableXxx}：本模块用最小的代价复刻 Spring 全家桶里
 * 「一个注解点亮一批 Bean」这条通用套路，让读者不用翻框架源码也能看见机关在哪。
 *
 * <p>定位：Spring 里所有 {@code @EnableScheduling}、{@code @EnableTransactionManagement}、
 * {@code @EnableConfigurationProperties}、{@code @EnableAutoConfiguration} 都是同一个形状——
 * 注解本身不含任何逻辑，真正的动作来自它头上的 {@code @Import}。
 * 实测（javap 读 class 里的注解常量）：
 * {@code @EnableConfigurationProperties} → {@code @Import(EnableConfigurationPropertiesRegistrar.class)}，
 * {@code @EnableAutoConfiguration} → {@code @Import(AutoConfigurationImportSelector.class)}。
 * 本类就是这条规律的手写最小版：{@code @Import(StartupFeatureSelector.class)}。</p>
 *
 * <p>关键实现：注解成员（这里是 {@code mode}）不是靠反射硬取，而是由
 * {@link StartupFeatureSelector#selectImports(AnnotationMetadata)} 的入参
 * {@code AnnotationMetadata} 直接给出——框架把「谁标注了我」连同属性值一起递给选择器，
 * 这就是 {@code @EnableXxx(mode = ...)} 能传参的全部原因。</p>
 *
 * <p>坑：① 注解必须 {@code RetentionPolicy.RUNTIME}，否则启动期反射读不到；
 * ② {@code @Target} 只写 TYPE 就够了，但 Boot 的很多 {@code @EnableXxx} 允许标在方法上
 * （{@code @Bean} 方法返回类型上做二次解析），别照抄成 TYPE-only 就断言 Spring 只能标类；
 * ③ 选择器返回的类名如果拼错，容器不会报「注解没生效」，而是在
 * {@code ConfigurationClassParser} 阶段抛 ClassNotFound，排查时先确认字符串常量。</p>
 *
 * <p>替代方案：不想写选择器时用 {@code @Import(具体配置类.class)} 直接点名，
 * 那就退化成「固定装配」，失去按参数/环境动态决定的能力。</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(StartupFeatureSelector.class)
public @interface EnableStartupFeature {

    /**
     * 装配档位。
     *
     * <p>{@code basic} 只注册一个最简 Bean；{@code full} 额外用
     * {@code @EnableConfigurationProperties} 把 {@code demo.startup.annotations.*}
     * 绑定成属性 Bean。选择器就是靠这个字符串决定返回哪个配置类。</p>
     */
    String mode() default "basic";
}
