package com.example.demo.rocketmq.support;

/**
 * 演示用的延迟工具：让消费者处理变慢，便于观察顺序和积压现象。
 */
public final class Delays {

    private Delays() {
    }

    /** 休眠指定毫秒；被中断时恢复中断标记，避免吞掉线程的中断信号。 */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
