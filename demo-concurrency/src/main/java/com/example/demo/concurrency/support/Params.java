package com.example.demo.concurrency.support;

/**
 * 参数校验：越界直接抛 {@link IllegalArgumentException}，由全局异常处理器转成 400。
 *
 * <p>刻意不做静默 clamp：教学实验里“传了 9999 个线程”应该立刻报错，
 * 而不是悄悄改成 32 后让读者误以为真的跑了 9999 个线程。</p>
 */
public final class Params {

    private Params() {
    }

    /**
     * 校验并返回 long 参数。
     *
     * @param value 实际传入值
     * @param min   允许的最小值（含）
     * @param max   允许的最大值（含）
     * @param name  参数名，用于错误信息
     * @return 原值，保证落在 [min, max]
     * @throws IllegalArgumentException 越界时抛出，消息里带合法范围与当前值
     */
    public static long between(long value, long min, long max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " 必须在 " + min + "~" + max + " 之间，当前=" + value);
        }
        return value;
    }

    /**
     * 校验并返回 int 参数，语义同 {@link #between(long, long, long, String)}。
     */
    public static int between(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " 必须在 " + min + "~" + max + " 之间，当前=" + value);
        }
        return value;
    }
}
