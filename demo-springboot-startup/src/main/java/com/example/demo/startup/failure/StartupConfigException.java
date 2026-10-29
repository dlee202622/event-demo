package com.example.demo.startup.failure;

/**
 * 场景 5 的“可被分析”异常：配置错误专用类型。
 *
 * <p>定位：{@code FailureAnalyzer} 按异常类型认领，自定义异常是接入分析器的唯一钩子。
 * 单独做一个类型而不是复用 {@code IllegalStateException}，
 * 才能让 {@link StartupConfigFailureAnalyzer} 精确认领、又不误伤框架自己抛的异常。</p>
 *
 * <p>坑：分析器只能看到 {@code SpringApplication.run()} 期间抛出的异常；
 * 同样的异常若在运行期接口里抛出，走的是一般 HTTP 500，不会打印友好报告。</p>
 *
 * @param message       错误摘要，会出现在 DESCRIPTION 段
 * @param propertyKey   出问题的配置项全名
 * @param illegalValue  实际读到的值，null 表示压根没配
 * @param remedy        给运维的修复动作，出现在 ACTION 段
 */
public class StartupConfigException extends RuntimeException {

    private final String propertyKey;

    private final String illegalValue;

    private final String remedy;

    public StartupConfigException(String message, String propertyKey, String illegalValue, String remedy) {
        // 不传 cause：配置类错误没有底层异常，硬塞一个会让堆栈更啰嗦且无信息量
        super(message);
        this.propertyKey = propertyKey;
        this.illegalValue = illegalValue;
        this.remedy = remedy;
    }

    public String getPropertyKey() {
        return propertyKey;
    }

    public String getIllegalValue() {
        return illegalValue;
    }

    public String getRemedy() {
        return remedy;
    }

    /** 是否属于“完全没配”，决定分析器用“缺失”还是“非法取值”的措辞 */
    public boolean isMissing() {
        return illegalValue == null || illegalValue.isBlank();
    }
}
