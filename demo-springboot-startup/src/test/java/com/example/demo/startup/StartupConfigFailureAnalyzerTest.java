package com.example.demo.startup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.example.demo.startup.failure.StartupConfigException;
import com.example.demo.startup.failure.StartupConfigFailureAnalyzer;

/**
 * 场景 5 断言：分析器直接吃异常对象即可测试，不必真的启动一个失败进程。
 *
 * <p>为什么这样测：{@code FailureAnalyzer} 的调用点在容器刷新失败之后，
 * 用 {@code @SpringBootTest} 去触发会得到一个起不来的上下文；
 * 单元级断言 description/action 更稳，也更贴近“报告写了什么”这个教学目标。</p>
 */
class StartupConfigFailureAnalyzerTest {

    private final StartupConfigFailureAnalyzer analyzer = new StartupConfigFailureAnalyzer();

    /** 非法取值：DESCRIPTION 要点名配置项和当前值，ACTION 要给出可执行动作 */
    @Test
    void explainsIllegalValue() {
        StartupConfigException cause = new StartupConfigException(
                "demo.startup.fail.mode 被显式设置为 analyzer", "demo.startup.fail.mode", "analyzer",
                "把配置项改为 none 正常启动");
        var analysis = analyzer.analyze(cause);
        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).contains("demo.startup.fail.mode=analyzer");
        assertThat(analysis.getAction()).contains("把配置项改为 none");
    }

    /** 完全没配：措辞切换成“缺失”，与非法取值区分开 */
    @Test
    void explainsMissingProperty() {
        StartupConfigException cause = new StartupConfigException(
                "缺少必填配置", "demo.startup.fail.mode", null, "补上配置项");
        var analysis = analyzer.analyze(cause);
        assertThat(analysis).isNotNull();
        assertThat(analysis.getDescription()).contains("缺失");
    }

    /** 认领范围必须严格：非本类型的异常（如端口占用）不能被这个分析器接走 */
    @Test
    void ignoresForeignExceptionTypes() {
        assertThat(analyzer.analyze(new IllegalStateException("别人的异常"))).isNull();
    }
}
