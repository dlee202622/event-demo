package com.example.demo.jvm.support;

/**
 * 内存单位换算的小工具。
 *
 * <p>JVM 的 MXBean 一律返回字节，而教学场景里读者要看的是「多少 MB」。
 * 统一在这里换算，是为了让所有接口的精度一致（保留两位小数），
 * 避免同一个数字在 /areas 和 /pools 两个接口里对不上。</p>
 */
public final class Units {

    private static final double BYTES_PER_MB = 1024.0 * 1024.0;

    private Units() {
    }

    /**
     * @param bytes 字节数，负数（如未设置上限时 MXBean 返回的 -1）按 0 处理
     * @return 保留两位小数的 MB 值
     */
    public static double mb(long bytes) {
        if (bytes <= 0) {
            return 0.0;
        }
        return Math.round(bytes / BYTES_PER_MB * 100.0) / 100.0;
    }

    /**
     * 无符号减法保护：峰值、使用量这类指标理论上单调，
     * 但 GC 并发期间 MXBean 的采样可能读到回退值，这里不让读者看到负的差值。
     */
    public static long delta(long after, long before) {
        return Math.max(0, after - before);
    }

    /**
     * MB → 字节，用的是 1 MB = 1024×1024 这一档。
     *
     * <p>JVM 的参数（{@code -Xmx64m}、{@code -XX:PretenureSizeThreshold=1m}）都按 1024 进位，
     * 实验里绝不能换成 10^6——那样 64 MB 会被算成 64,000,000 字节，
     * 比真实的 67,108,864 少掉近 3 MB，所有「正好把堆填满」的实验都会差一点而不变不成。</p>
     */
    public static long mbToBytes(long megabytes) {
        return megabytes * (long) BYTES_PER_MB;
    }

    /**
     * 用于日志与脚本输出的人类可读大小；1 GB 以上用 GB，否则用 MB。
     */
    public static String human(long bytes) {
        double m = bytes / BYTES_PER_MB;
        if (m >= 1024.0) {
            return String.format("%.2f GB", m / 1024.0);
        }
        return String.format("%.2f MB", m);
    }
}
