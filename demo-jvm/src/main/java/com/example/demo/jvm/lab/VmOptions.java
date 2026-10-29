package com.example.demo.jvm.lab;

import com.sun.management.HotSpotDiagnosticMXBean;

import java.lang.management.ManagementFactory;

/**
 * 实验类读 JVM 参数的统一入口，只依赖 JDK。
 *
 * <p>为什么不复用 {@code support.JvmFlags}：那个类挂了 {@code @Component}，
 * 是给 Web 观测接口用的；实验类要能用 {@code java -cp target/classes} 裸跑，
 * 那种情况下 classpath 里没有 Spring。两处各留一个薄壳，比让实验去依赖容器干净。</p>
 *
 * <p>坑（都实测过）：
 * <ul>
 *   <li>{@code getValue()} 返回字符串，<strong>单位随参数而变</strong>：
 *       {@code ThreadStackSize} 是 KB（默认 1024 = 1 MB），
 *       {@code MaxMetaspaceSize}、{@code MaxDirectMemorySize} 是字节；</li>
 *   <li>{@code MaxDirectMemorySize=0} 不是「不给用」，而是「按 {@code -Xmx} 算」；
 *       {@code MaxMetaspaceSize=0} 才是真的无上限（只受物理内存限制）；</li>
 *   <li>非 HotSpot 的 VM 没有这个 MBean，或者参数名不存在时会抛异常——
 *       读不到就返回 {@code null}/{@code -1}，让报告承认量不到，而不是把实验搞崩。</li>
 * </ul></p>
 */
public final class VmOptions {

    private static final HotSpotDiagnosticMXBean DIAGNOSTIC =
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);

    private VmOptions() {
    }

    /**
     * @param name 参数名，如 {@code MaxMetaspaceSize}
     * @return 原始字符串值；参数不存在或 VM 不支持时返回 null
     */
    public static String raw(String name) {
        try {
            return DIAGNOSTIC.getVMOption(name).getValue();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 数值型参数。
     *
     * <p>坑：返回 {@code null} 和返回 {@code -1} 是两件不同的事。
     * {@code null} 是「参数不存在 / 不是数字 / 不是 HotSpot」；
     * {@code -1} 是参数<em>存在且值真的是 -1</em>——实测 {@code MaxMetaspaceSize} 没设上限时
     * 就是 {@code "-1"}，不是缺失。混淆这两者会把「不限」报告成「读不到」。</p>
     *
     * @param name 参数名
     * @return 参数值；读不到返回 null
     */
    public static Long number(String name) {
        String value = raw(name);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            // 布尔旗标在这里会是 "true"/"false"，按数字读必然失败，交回调用方判断
            return null;
        }
    }

    /**
     * 把「按字节」的参数换算成 MB，并把哨兵值翻译成人类能懂的话。
     *
     * @param name 参数名，单位必须是字节
     * @return 形如 {@code 48.0 MB} / {@code -1(不限)} / {@code 0(默认值…)} / {@code (读不到)}
     */
    public static String describeBytes(String name) {
        Long bytes = number(name);
        if (bytes == null) {
            return "(读不到)";
        }
        if (bytes == -1L) {
            // MaxMetaspaceSize 的「无上限」在 HotSpot 里就是 -1
            return "-1(不限)";
        }
        if (bytes == 0L) {
            // 0 有两种含义：Metaspace 类参数是「不限」，MaxDirectMemorySize 是「跟 -Xmx 走」，
            // 这里不替调用方猜，原样报 0 并注明含义要看参数
            return "0(默认值，含义见该参数说明)";
        }
        return com.example.demo.jvm.support.Units.mb(bytes) + " MB";
    }
}
