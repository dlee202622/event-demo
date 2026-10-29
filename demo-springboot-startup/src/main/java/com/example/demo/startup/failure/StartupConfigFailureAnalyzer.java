package com.example.demo.startup.failure;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * 场景 5 的主角：把 {@link StartupConfigException} 翻译成“人能照做”的三行报告。
 *
 * <p>定位：启动失败时 Boot 会用 {@code FailureAnalyzers} 遍历所有注册的
 * {@code FailureAnalyzer}，第一个认领异常类型的分析器决定控制台输出什么。
 * 有了它，堆栈墙变成 DESCRIPTION / ACTION 两段可执行信息。</p>
 *
 * <p>适用场景：配置项缺失/非法、必填密钥为空、环境不满足前提这类
 * “运维一看就知道怎么改”的错误。框架内部错误请交给官方 analyzer
 * （如内置的 {@code PortInUseFailureAnalyzer}）。</p>
 *
 * <p>关键实现：继承 {@code AbstractFailureAnalyzer<T>} 而不是直接实现接口——
 * 父类负责在异常链上按类型下钻查找，本类只需处理确定类型的实例，
 * 免去自己写 cause 遍历。</p>
 *
 * <p>坑：① 必须在 {@code META-INF/spring.factories} 注册；写成 {@code @Component} 无效，
 * 因为 analyzer 在容器建立失败后运行，容器里的 Bean 取不到；
 * ② 多个 analyzer 认领同一异常时只取一个（无固定顺序保证），别指望“全都执行”；
 * ③ 认领靠泛型参数上的异常类型，父类沿 cause 链下钻匹配——所以业务代码要把真实原因
 * 包成 {@code cause} 抛出，裸抛一个字符串消息的自定义异常没人认领，输出仍是满屏堆栈。</p>
 */
public class StartupConfigFailureAnalyzer extends AbstractFailureAnalyzer<StartupConfigException> {

    /**
     * @param rootFailure 异常链顶端，父类已遍历定位到 {@link StartupConfigException}
     * @param cause       匹配到的实例
     * @return 非 null 即宣告“我认领”，控制台改为打印本报告的 description/action
     */
    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, StartupConfigException cause) {
        String description = cause.isMissing()
                ? "必需的配置项缺失：\n  " + cause.getPropertyKey()
                : "配置项取值非法：\n  " + cause.getPropertyKey() + "=" + cause.getIllegalValue();
        String action = cause.getRemedy()
                + "\n\n（本报告由自定义 StartupConfigFailureAnalyzer 生成；"
                + "同一异常若没有认领的 analyzer，控制台会打印完整堆栈——见场景 5 的 raw 对照组）";
        // 第三个参数 cause 会作为 cause 挂在 FailureAnalysis 上，供自定义 reporter/审计读取。
        // 实测：一旦被认领，控制台就只剩这份报告——analyzer 那次 42 行日志、0 条堆栈帧，
        // raw 对照组 63 行、30 条堆栈帧 + 3 层 Caused by；报告本身不附带堆栈
        return new FailureAnalysis(description, action, cause);
    }
}
