package com.example.demo.startup.annotations.feature;

import java.util.List;

/**
 * {@code @EnableStartupFeature} 装配出来的产品 Bean，只有一个用途：
 * 让接口能回答「这个档位到底给了我什么」。
 *
 * <p>定位：教学 demo 里最常见的失败是「注解加了，但看不见效果」。
 * 与其让读者去数 Bean 总数（会被其它场景的 Bean 干扰），不如让两种档位各产出
 * 一个自带身份信息的 Bean：{@code tier} 说明它来自哪个配置类，
 * {@code boundNames} / {@code boundRetries} 说明属性绑定有没有发生。</p>
 *
 * <p>关键实现：不可变（final 字段 + {@code List.copyOf}），
 * 因为它会被两个不同的 ApplicationContext（主应用 + 子应用）分别持有，
 * 任何可变状态都会让「谁创建了我」变得无法判断。</p>
 *
 * <p>坑：Bean 的名字来自 {@code @Bean} 方法名，不是类名。
 * basic 档叫 {@code startupFeatureApiBasic}、full 档叫 {@code startupFeatureApiFull}，
 * 所以 {@code getBean(StartupFeatureApi.class)} 能拿到，但 {@code getBean("startupFeatureApi")} 会失败。</p>
 */
public class StartupFeatureApi {

    /** 档位标识：basic / full，等于选择器当时返回的配置类 */
    private final String tier;

    /** full 档才有值（来自 @ConfigurationProperties 绑定）；basic 档是空列表 */
    private final List<String> boundNames;

    /** full 档才有值；basic 档固定 0，用于对比「属性到底有没有进来」 */
    private final int boundRetries;

    public StartupFeatureApi(String tier, List<String> boundNames, int boundRetries) {
        this.tier = tier;
        // copyOf：防止属性 Bean 后续被改写字段后，这里看到的值跟着变
        this.boundNames = List.copyOf(boundNames);
        this.boundRetries = boundRetries;
    }

    public String tier() {
        return tier;
    }

    public List<String> boundNames() {
        return boundNames;
    }

    public int boundRetries() {
        return boundRetries;
    }
}
