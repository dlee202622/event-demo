package com.example.demo.startup.autoconfig;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.Conditional;

/**
 * 场景 3 的三种条件形态宿主：属性开关注解 / 自定义条件 / 无条件。
 *
 * <p>定位：{@code ConditionEvaluationReport} 是按“声明 Bean 的位置（source）”
 * 记录判定结果的，本类三个 @Bean 方法刻意分三种写法，让报告里同时出现
 * 匹配、不匹配、未记录三种形态，接口对比一目了然。</p>
 *
 * <p>关键实现：开关默认全关（matchIfMissing=false），
 * 打开方式是环境变量或启动参数（见 README），改一行 yaml 重启即可对比报告差异。</p>
 *
 * <p>坑：① 同一个配置类里多个 @Bean 会共享类级条件，方法级条件覆盖类级；
 * ② {@code @ConditionalOnProperty} 只做字符串比较，不会把 “TRUE”“1” 之外的
 * 奇怪写法当 true；③ 条件类一旦被报告记录，改代码不重启数字不会变——
 * 报告反映的是那次启动的快照。</p>
 */
@Configuration(proxyBeanMethods = true)
public class AutoConfigDemoConfig {

    /**
     * 形态一：官方注解开关。havingValue="true" 且未写 matchIfMissing，
     * 默认值是 false —— 缺省启动时这个 Bean 不存在，报告里能看到
     * OnPropertyCondition 的 noMatch 消息（“@ConditionalOnProperty check failed”）。
     */
    @Bean
    @ConditionalOnProperty(name = "demo.startup.autoconfig.property-toggle", havingValue = "true")
    public PropertyToggleBean propertyToggleBean() {
        return new PropertyToggleBean("由 @ConditionalOnProperty 装载");
    }

    /**
     * 形态二：自定义条件。演示 Spring 的 {@code @Conditional} 挂载点，
     * 判定与解释都由 {@link FeatureColorCondition} 自己给出。
     */
    @Bean
    @Conditional(FeatureColorCondition.class)
    public GrayReleaseBean grayReleaseBean() {
        return new GrayReleaseBean("由 FeatureColorCondition 装载");
    }

    /**
     * 形态三：无条件。报告里没有它的条目——“没记录”不等于“没装载”，
     * 这是初学者看 Condition Evaluation Report 时最容易误读的一点。
     */
    @Bean
    public AlwaysOnBean alwaysOnBean() {
        return new AlwaysOnBean();
    }

    /** 属性开关 Bean 本体 */
    public static class PropertyToggleBean {
        private final String installedBy;

        public PropertyToggleBean(String installedBy) {
            this.installedBy = installedBy;
        }

        public String getInstalledBy() {
            return installedBy;
        }
    }

    /** 灰度 Bean 本体 */
    public static class GrayReleaseBean {
        private final String installedBy;

        public GrayReleaseBean(String installedBy) {
            this.installedBy = installedBy;
        }

        public String getInstalledBy() {
            return installedBy;
        }
    }

    /** 无条件 Bean 本体（对照组） */
    public static class AlwaysOnBean {
    }
}
