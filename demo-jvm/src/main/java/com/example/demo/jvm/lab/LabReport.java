package com.example.demo.jvm.lab;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实验输出的统一格式：一行一个 {@code key=value}。
 *
 * <p>为什么不用 JSON：实验类必须能被最朴素的命令跑起来
 * （{@code java -cp demo-jvm/target/classes ...}，不带任何依赖），
 * 而 {@code target/classes} 里不含 Jackson。key=value 既方便人读，
 * 也方便 shell 用 {@code grep '^gc.count='} 提取，测试里一行 {@code split} 就能断言。</p>
 *
 * <p>约定：键名用小写加点分隔（{@code gc.count}、{@code pause.maxMs}），
 * 值里不出现换行；多行原文（如 gc.log 片段）不进报告，另存文件。</p>
 */
public final class LabReport {

    private final String lab;
    private final Map<String, Object> values = new LinkedHashMap<>();

    private LabReport(String lab) {
        this.lab = lab;
    }

    /**
     * @param lab 实验名，会打成首行 {@code # lab=xxx}；和教程里的命令一一对应，
     *            这样读者贴出来的输出能立刻看出是哪个实验跑的
     */
    public static LabReport of(String lab) {
        return new LabReport(lab);
    }

    /**
     * @param key   形如 {@code pause.maxMs}
     * @param value 数字、布尔或字符串；null 会被写成 {@code (null)}，让「没有值」也可见
     */
    public LabReport put(String key, Object value) {
        values.put(key, value);
        return this;
    }

    /**
     * 保留两位小数的浮点值，避免表格里出现 3.456000000001 这种噪音。
     * 单独一个方法而不是重载 {@code put}：重载会让 {@code put(k, 1)} 这类调用
     * 在「装箱成 Object」和「加宽成 double」之间摇摆，可读性差。
     */
    public LabReport putMb(String key, double megabytes) {
        return putRound(key, megabytes);
    }

    /** 毫秒值，同样保留两位——暂停时间常有 0.3 ms 这种量级，取整会全变成 0。 */
    public LabReport putMs(String key, double millis) {
        return putRound(key, millis);
    }

    /** 通用两位小数：单位由键名自己说明（例如 {@code frame.bytesFitted}）。 */
    public LabReport putRound(String key, double value) {
        values.put(key, Math.round(value * 100.0) / 100.0);
        return this;
    }

    /**
     * 字节值：帧大小、对象大小这类「个位数 KB」的量用 MB 表示会全是 0.00，
     * 所以保留一个原样输出字节的入口，键名以 {@code Bytes} 结尾表明单位。
     */
    public LabReport putBytes(String key, long bytes) {
        values.put(key, bytes);
        return this;
    }

    /**
     * 打印报告。用 {@code System.out}，这样 {@code ./mvnw} 与脚本都能直接捕获；
     * 报告本身带 {@code #} 注释头，方便读者把输出直接贴进文档。
     */
    public void print() {
        print(System.out);
    }

    /**
     * 打到指定流。
     *
     * <p>父进程（{@link ChildJvm} 那一侧）把子进程报告转抄进自己的输出时用这条；
     * 无参版本固定用 {@code System.out}，因为实验的约定就是「正文走 stdout，
     * 告警走 stderr」，测试捕获时两者不能混。</p>
     */
    public void print(PrintStream out) {
        out.println("# lab=" + lab);
        values.forEach((key, value) -> out.println(key + "=" + (value == null ? "(null)" : value)));
        out.flush();
    }

    /**
     * 反向解析：测试和脚本把捕获到的 stdout 还原成 map。
     *
     * @param text 进程输出全文
     * @return 键值对；注释行（以 # 开头）与空行被忽略，重复键取最后一次
     */
    public static Map<String, String> parse(String text) {
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String line : text.lines().toList()) {
            String trimmed = line.trim();
            int split = trimmed.indexOf('=');
            if (trimmed.isEmpty() || trimmed.startsWith("#") || split <= 0) {
                continue;
            }
            parsed.put(trimmed.substring(0, split), trimmed.substring(split + 1));
        }
        return parsed;
    }
}
