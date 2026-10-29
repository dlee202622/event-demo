package com.example.demo.startup.annotations.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.DeferredImportSelector;
import org.springframework.context.annotation.ImportSelector;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.lang.NonNull;

/**
 * {@code @EnableStartupFeature} 背后的那个类——本场景的主角：
 * 注解只是「入口」，真正决定往容器里塞什么的是 {@link ImportSelector}。
 *
 * <p>定位：Spring 把 {@code @Import} 的四种写法分派给四种处理者：
 * ① 直接给配置类 → 注册该类；② 给 {@code ImportSelector} → 调
 * {@code selectImports} 拿类名字符串数组（本类）；③ 给 {@code DeferredImportSelector}
 * → 同上，但推迟到所有普通配置解析完之后再执行（自动配置走这条）；
 * ④ 给 {@code ImportBeanDefinitionRegistrar} → 自己手写 {@code registerBeanDefinitions}
 * 直接往注册器里塞定义。本模块只用②，但用反射把③的证据取出来
 * （见 {@code StartupFeatureSelector#autoConfigSelectorIsDeferred}），
 * 让读者看见「自动配置凭什么排在最后」。</p>
 *
 * <p>关键实现：入参 {@code importingClassMetadata} 就是标注了本注解的那个类的元数据，
 * {@code getClassName()} 告诉我们「谁启用的」，
 * {@code getAnnotationAttributes(FQN)} 告诉我们「带什么参数启用的」。
 * 两者都被记进 {@link #HISTORY}，接口 {@code /api/startup/annotations/feature}
 * 原样回显，于是「注解 → 选择器 → 配置类」这条链在运行时是可证的，不是口头解释。</p>
 *
 * <p>坑：① 返回值是类名而不是 Class 对象，拼错会在解析期抛 ClassNotFound，
 * 报错信息里看不到注解名，很容易误以为「注解没生效」；
 * ② 选择器被调用时容器还没开始实例化任何 Bean，因此在这里 {@code getBean} 一定拿不到东西，
 * 只能读元数据；③ 属性名必须用注解的<b>全限定名</b>查，
 * {@code getAnnotationAttributes} 按 FQN 建索引，用简单类名取到的是空 Map；
 * ④ 同一个选择器可能被多个类触发，{@code HISTORY} 用静态列表就是为了让读者数清自己被调了几次。</p>
 *
 * <p>替代方案：固定装配用 {@code @Import(XxxConfiguration.class)}；
 * 需要按条件延迟用 {@code DeferredImportSelector}；
 * 需要精细控制 Bean 定义（动态名字、动态作用域）用 {@code ImportBeanDefinitionRegistrar}。</p>
 */
public class StartupFeatureSelector implements ImportSelector {

    /** 每次被调用的现场记录：谁标注、mode 是什么、返回了哪些类名 */
    public record Resolution(String importingClass, String mode, List<String> imports) {
    }

    /** 静态收集：选择器本身不是业务 Bean，只能靠静态列表在接口里回看解析历史 */
    private static final List<Resolution> HISTORY = Collections.synchronizedList(new ArrayList<>());

    @NonNull
    @Override
    public String[] selectImports(@NonNull AnnotationMetadata importingClassMetadata) {
        // 传全限定名而不是简单类名：见类注释坑③
        Map<String, Object> attributes = importingClassMetadata
                .getAnnotationAttributes(EnableStartupFeature.class.getName());
        String mode = attributes == null ? "basic" : String.valueOf(attributes.getOrDefault("mode", "basic"));

        String target = switch (mode) {
            // full 档额外带 @EnableConfigurationProperties，用来演示「档位不同，导入的类不同」
            case "full" -> StartupFeatureFullConfig.class.getName();
            default -> StartupFeatureBasicConfig.class.getName();
        };
        HISTORY.add(new Resolution(importingClassMetadata.getClassName(), mode, List.of(target)));
        return new String[] { target };
    }

    /** 解析历史快照（不可变副本），供控制器回显 */
    public static List<Resolution> history() {
        synchronized (HISTORY) {
            return List.copyOf(HISTORY);
        }
    }

    /**
     * 给读者的对照证据：自动配置用的 {@code AutoConfigurationImportSelector}
     * 实现的是 {@link DeferredImportSelector}（本类只是普通 {@link ImportSelector}），
     * 这正是「自动配置在所有用户配置解析完之后才展开、所以条件报告能看到用户 Bean」的机制来源。
     */
    public static boolean autoConfigSelectorIsDeferred() {
        return DeferredImportSelector.class.isAssignableFrom(loadAutoConfigSelector());
    }

    /** 自动配置选择器的类名（属于 spring-boot-autoconfigure 模块） */
    public static String autoConfigSelectorClassName() {
        return loadAutoConfigSelector().getName();
    }

    private static Class<?> loadAutoConfigSelector() {
        try {
            return Class.forName("org.springframework.boot.autoconfigure.AutoConfigurationImportSelector");
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException(
                    "spring-boot-autoconfigure 不在类路径上，无法演示 DeferredImportSelector 对照", ex);
        }
    }
}
