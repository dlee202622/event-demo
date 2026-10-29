package com.example.demo.startup.annotations.feature;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 场景 0 的属性载体：只给 full 档用，目的是把
 * {@code @ConfigurationProperties} 这条「注解 → 绑定器 → Bean」链路变成可见数字。
 *
 * <p>定位：{@code @ConfigurationProperties} 本身不注册 Bean，
 * 它需要两样东西同时到位：① 有类把它注册成 Bean Definition
 * （{@code @EnableConfigurationProperties} 走 {@code @Import(EnableConfigurationPropertiesRegistrar)}，
 * 或者类自己标 {@code @Component}）；② {@code ConfigurationPropertiesBindingPostProcessor}
 * 在场，负责在 Bean 创建后把属性灌进去——后者由自动配置类
 * {@code ConfigurationPropertiesAutoConfiguration}（在 {@code AutoConfiguration.imports} 里）注册。
 * 少任何一个，表现都是「Bean 有、字段是默认值」，而不是报错。</p>
 *
 * <p>关键实现：字段留默认值（{@code retries = 1}、{@code names = List.of()}），
 * 这样接口就能区分「没绑上（默认值 1）」和「绑上了（yaml 的 2）」，
 * 而不是靠 null 判断——构造器绑定/不可变绑定是 Boot 推荐的另一种写法，本 demo 用
 * setter 绑定最直观。</p>
 *
 * <p>坑：① prefix 必须和 yaml 层级严格对应，写错不报错，只是静默不绑定；
 * ② {@code List<String>} 支持 {@code names: alpha,beta,gamma} 这种逗号写法
 * （Binder 会按 {@code [0] [1] [2]} 索引解析），但也支持 yaml 数组写法，别以为只能一种；
 * ③ 松散绑定：yaml 里写 {@code feature-label}、{@code featureLabel}、{@code FEATURE_LABEL}
 * 都能绑到 {@code featureLabel}，这也是环境变量能覆盖属性的原因。</p>
 */
@ConfigurationProperties(prefix = "demo.startup.annotations")
public class StartupFeatureProperties {

    /** 逗号分隔或 yaml 数组均可；用于观察 Binder 的索引解析 */
    private List<String> names = List.of();

    /** 数字：命令行 --demo.startup.annotations.retries=7 会覆盖 yaml 的 2，用来对照优先级 */
    private int retries = 1;

    /** 松散绑定的观察点：属性名 feature-label / featureLabel / FEATURE_LABEL 等价 */
    private String featureLabel = "unset";

    public List<String> getNames() {
        return names;
    }

    public void setNames(List<String> names) {
        this.names = names;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int retries) {
        this.retries = retries;
    }

    public String getFeatureLabel() {
        return featureLabel;
    }

    public void setFeatureLabel(String featureLabel) {
        this.featureLabel = featureLabel;
    }
}
