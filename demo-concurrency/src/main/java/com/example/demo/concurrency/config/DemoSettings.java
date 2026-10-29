package com.example.demo.concurrency.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 演示实验的硬上限，全部支持环境变量覆盖。
 *
 * <p>这些值只用于保护观测进程：例如线程数过多会让“谁先抢到锁”变成调度噪声，
 * 迭代次数过大则会让一次 HTTP 请求长时间占满 CPU。它们不是业务参数。</p>
 *
 * @param maxThreads             单次实验允许的最大工作线程数
 * @param maxHoldMs              持锁 / 阻塞类实验的最大毫秒数
 * @param maxIterations          计数实验单线程最大迭代次数
 * @param counterRounds          吞吐对比默认轮数（每轮取一次耗时，最后取中位数）
 * @param deadlockDetectTimeoutMs 死锁检测的最长轮询时间（毫秒）
 */
@ConfigurationProperties(prefix = "demo.concurrency")
public record DemoSettings(int maxThreads, long maxHoldMs, int maxIterations,
                           int counterRounds, long deadlockDetectTimeoutMs) {
}
