package com.example.demo.redis.ratelimit;

import com.example.demo.redis.support.RateLimitDecision;

import java.util.Map;

/**
 * 限流算法统一接口：同一份 {limit, window} 参数下，对比不同策略的放行结果。
 *
 * <p>实现类必须保证 {@link #tryAcquire} 在并发下是原子的；本仓库统一用 Lua 做到这一点。
 * 同一个 key 的所有请求共享同一份计数，不同算法的 key 互不干扰。</p>
 */
public interface RateLimiter {

    /** 算法标识，同时用于 URL 和 Redis key 前缀。 */
    String name();

    /** 一句话说明实现方式与代价。 */
    String description();

    /**
     * 尝试获取一次通行资格。
     *
     * <p>调用即消耗一次判定：被拒绝也算一次尝试，但不会占用名额。</p>
     *
     * @param key           业务维度（用户、IP、接口名）；同一 key 共享额度
     * @param limit         窗口内允许的请求数 / 桶容量，必须 ≥1
     * @param windowSeconds 窗口长度（秒），令牌桶/漏桶据此换算速率
     * @return 本次判定结果，包含是否放行、剩余额度、建议重试等待时间
     */
    RateLimitDecision tryAcquire(String key, long limit, long windowSeconds);

    /**
     * 查看该算法在 Redis 里的真实数据结构。
     *
     * <p>教学用途：固定窗口/分桶/令牌桶是 HASH，滑动日志是 ZSET。
     * 返回结构随算法而变，字段见 {@code AbstractLuaRateLimiter#state}。</p>
     */
    Map<String, Object> state(String key);
}
