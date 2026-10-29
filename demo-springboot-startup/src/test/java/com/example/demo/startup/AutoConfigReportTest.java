package com.example.demo.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.example.demo.startup.autoconfig.AutoConfigDemoConfig.AlwaysOnBean;
import com.example.demo.startup.autoconfig.AutoConfigDemoConfig.GrayReleaseBean;
import com.example.demo.startup.autoconfig.AutoConfigDemoConfig.PropertyToggleBean;
import com.example.demo.startup.autoconfig.AutoConfigService;

/**
 * 场景 3 断言：条件报告可用，三种条件形态的实际装载结果与默认配置一致。
 */
@SpringBootTest
class AutoConfigReportTest {

    @Autowired
    private AutoConfigService autoConfigService;

    @Autowired
    private org.springframework.context.ApplicationContext context;

    /** 报告一定可用：条件求值无条件记录，debug 只影响日志打印 */
    @Test
    void reportIsAlwaysAvailable() {
        AutoConfigService.ReportSummary summary = autoConfigService.summary();
        assertThat(summary.available()).isTrue();
        // 一个真实的 Boot 应用动辄上百个候选配置类，少于 50 说明读错了容器
        assertThat(summary.totalSources()).isGreaterThan(50);
    }

    /** 默认配置下：属性开关关、颜色非 green，因此只有无条件 Bean 存在 */
    @Test
    void defaultPropertiesKeepConditionalBeansAbsent() {
        assertThat(context.containsBean("propertyToggleBean")).isFalse();
        assertThat(context.containsBean("grayReleaseBean")).isFalse();
        assertThat(context.getBean(AlwaysOnBean.class)).isNotNull();
    }

    /** 自定义条件的解释文本要出现在报告里——这是“为什么没装载”的核心证据 */
    @Test
    void customConditionMessageIsRecorded() {
        List<AutoConfigService.ConditionEntry> hits = autoConfigService.search("FeatureColor", 20);
        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().message()).contains("demo.startup.autoconfig.feature-color");
        assertThat(hits.getFirst().matched()).isFalse();
    }

    /**
     * 排除 DispatcherServletAutoConfiguration 的实际后果（Boot 4.1.2 实测）：
     * baseline 200，excluded 直接启动失败——ErrorMvcAutoConfiguration 依赖它提供的
     * DispatcherServletPath。断言写的是“实测结论”，不是“教程预想结论”。
     */
    @Test
    void excludingDispatcherServletBreaksStartup() {
        AutoConfigService.ExcludeDemoResult result = autoConfigService.excludeDemo();
        assertThat(result.baselineStatus()).isEqualTo(200);
        assertThat(result.excludedOutcome()).isEqualTo("FAILED");
        assertThat(result.excludedFailureMessage()).contains("DispatcherServletPath");
    }

    /** PropertyToggleBean / GrayReleaseBean 类型本身要可引用（防止条件写错导致类被删） */
    @Test
    void conditionalTypesExist() {
        assertThat(PropertyToggleBean.class).isNotNull();
        assertThat(GrayReleaseBean.class).isNotNull();
    }
}
