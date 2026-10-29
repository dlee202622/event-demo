package com.example.demo.startup.modes;

/**
 * 一种启动模式的实测快照（场景 2 的返回单元）。
 *
 * @param mode              NONE / SERVLET / REACTIVE，或 “AUTO-DEDUCED”（不显式指定时的推断结果）
 * @param contextClass      容器实现类简名——模式最直接的身份证：
 *                          AnnotationConfigApplicationContext / AnnotationConfigServletWebServerApplicationContext /
 *                          AnnotationConfigReactiveWebServerApplicationContext
 * @param beanCount         refresh 完成时的 Bean 定义数，用数字量化“Web 模式多装了哪些基础设施 Bean”
 * @param webServer         Web 服务器实现简名；NONE 模式为 null，这是“没起服务器”的证据
 * @param port              实际监听端口；NONE 为 null，port=0 时为内核分配的随机端口
 * @param startupMillis     从 new SpringApplication 到 run() 返回的墙钟毫秒（含重复的自动配置解析，故非纯净耗时）
 * @param outcome           OK 或 FAILED
 * @param failureClass      失败时的异常类简名
 * @param failureMessage    失败时的异常消息原文（教学重点是“看原文”，不做改写）
 * @param rootCauseClass    失败时根因异常类简名
 * @param rootCauseMessage  根因异常消息——顶层常是“Failed to start bean ...”，
 *                          真正的原因（如 “Address already in use”）只在根因里
 * @param note              该模式的适用场景一句话
 */
public record ModeSnapshot(String mode, String contextClass, Integer beanCount, String webServer, Integer port,
        long startupMillis, String outcome, String failureClass, String failureMessage,
        String rootCauseClass, String rootCauseMessage, String note) {

    /** 成功快照：只关心容器/端口/耗时，失败相关字段留空 */
    static ModeSnapshot ok(String mode, String contextClass, Integer beanCount, String webServer, Integer port,
            long startupMillis, String note) {
        return new ModeSnapshot(mode, contextClass, beanCount, webServer, port, startupMillis,
                "OK", null, null, null, null, note);
    }

    /** 失败快照：容器没能起来，把异常链上的关键信息原样带出 */
    static ModeSnapshot failed(String mode, long startupMillis, Throwable ex, String note) {
        Throwable root = ex;
        // 沿 getCause 下钻找根因；限定 8 层防御异常链成环（环会导致死循环）
        for (int i = 0; i < 8 && root.getCause() != null && root.getCause() != root; i++) {
            root = root.getCause();
        }
        return new ModeSnapshot(mode, null, null, null, null, startupMillis,
                "FAILED", ex.getClass().getSimpleName(), ex.getMessage(),
                root.getClass().getSimpleName(), root.getMessage(), note);
    }
}
