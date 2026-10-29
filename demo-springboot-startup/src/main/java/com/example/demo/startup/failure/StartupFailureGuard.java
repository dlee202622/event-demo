package com.example.demo.startup.failure;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 场景 5 的开关：按配置项决定“让不让应用启动成功”。
 *
 * <p>定位：启动失败无法用 HTTP 接口触发——失败的应用压根没起来，也就没有接口。
 * 所以本类的判定点放在 {@code @PostConstruct}（属于容器刷新阶段，异常会走完整的
 * 启动失败链路：{@code handleRunFailure} → FailureAnalyzers → 报告），
 * 由外部用环境变量重启进程来观测两种结局。</p>
 *
 * <p>关键实现：mode 通过 {@code @Value} 读取而不是 Environment.getBean，
 * 保证在任何其他 Bean 之前就能判定；抛异常前不做任何清理动作，
 * 让 Boot 的失败处理路径保持“原生态”。</p>
 *
 * <p>坑：① 默认值必须是 none，否则 CI/日常启动直接挂；
 * ② mode 写错拼写会被当未知值放行（见 allowed 校验的分支），
 * 排查时先确认自己传的是三种取值之一；
 * ③ raw 模式故意构造了 3 层 cause 链，为的是让“满屏堆栈”这个对照组更真实。</p>
 */
@Component
public class StartupFailureGuard {

    /** 允许的模式；不在列表内的一律当 none 处理，避免手滑拼错导致永久起不来 */
    private static final List<String> ALLOWED = List.of("none", "analyzer", "raw");

    private final String mode;

    public StartupFailureGuard(@Value("${demo.startup.fail.mode:none}") String mode) {
        this.mode = (mode == null) ? "none" : mode.trim().toLowerCase();
    }

    /**
     * 启动期检查点。
     *
     * @throws StartupConfigException     mode=analyzer 时抛，被自定义分析器接住打印友好报告
     * @throws IllegalStateException      mode=raw 时抛，无分析器认领，控制台输出原始堆栈
     */
    @PostConstruct
    public void check() {
        if (!ALLOWED.contains(mode)) {
            // 未知取值不打断启动：只留一行警告，教学 demo 不该因为拼错属性把自己锁死
            System.err.println("[startup-failure-demo] 未知 fail.mode=" + mode + "，按 none 处理；可选值 " + ALLOWED);
            return;
        }
        switch (mode) {
            case "analyzer" -> throw new StartupConfigException(
                    "demo.startup.fail.mode 被显式设置为 analyzer（本模块的场景 5 演示）",
                    "demo.startup.fail.mode",
                    "analyzer",
                    "把配置项改为 none 正常启动；analyzer/raw 只用于演示两种失败输出。"
                            + "临时验证命令见 README 第 6 节");
            case "raw" -> throw new IllegalStateException(
                    "没有分析器认领的裸异常：模拟第三方库在初始化时抛出的错误",
                    // 三层 cause：制造“unwrapped chain”效果，和 analyzer 模式的三行输出形成对照
                    new RuntimeException("底层调用失败",
                            new java.net.ConnectException("connection refused (simulated)")));
            default -> {
                // none：正常启动
            }
        }
    }
}
