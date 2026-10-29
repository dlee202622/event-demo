package com.example.demo.startup.autoconfig;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * 场景 3 的核心教具：自定义条件，演示“条件自己解释为什么”。
 *
 * <p>定位：普通 Spring {@code Condition} 只回答 true/false；继承 Boot 的
 * {@link SpringBootCondition} 后，框架会自动把 {@link #getMatchOutcome} 返回的
 * {@link ConditionOutcome}（布尔 + 消息）记进 {@code ConditionEvaluationReport}，
 * {@code /api/startup/autoconfig/search?contains=FeatureColor} 里就能直接看到
 * 中文判定依据，而不是干巴巴一个 false。</p>
 *
 * <p>适用场景：按租户/机房/灰度开关裁剪 Bean。Boot 内置的
 * {@code @ConditionalOnProperty} 等注解本质也是 SpringBootCondition 子类，
 * 官方注解够用就不要自己写。</p>
 *
 * <p>坑：① 条件在 Bean 定义注册阶段求值，远早于 Bean 实例化——
 * {@code getMatchOutcome} 里绝不能 getBean()，那时目标 Bean 还不存在；
 * ② 同一个条件实例会被多个 @Bean 方法复用，必须无状态；
 * ③ 抛异常会让应用启动失败，读取属性要用带默认值的安全方式。</p>
 */
public class FeatureColorCondition extends SpringBootCondition {

    /** 属性键：颜色值里含 green 即视为启用灰度特性 */
    static final String PROPERTY = "demo.startup.autoconfig.feature-color";

    /**
     * SpringBootCondition.matches() 是 final 语义的模板方法：
     * 它调用 getMatchOutcome、记录报告、再取 isMatch()——子类只写判定和解释。
     */
    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        // context.getEnvironment() 是条件阶段唯一安全的取值通道：
        // 此时属性源已全部就位（environmentPrepared 早已完成）
        String value = context.getEnvironment().getProperty(PROPERTY);
        // null 要显式处理：getProperty 缺失时返回 null，直接 equals 会 NPE 炸掉启动
        boolean matched = value != null && value.toLowerCase().contains("green");
        String explain = "属性 " + PROPERTY + " 当前值=\"" + value + "\"，判定规则=值包含 green";
        return matched ? ConditionOutcome.match(explain) : ConditionOutcome.noMatch(explain);
    }
}
