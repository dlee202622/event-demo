package com.example.demo.redis.ratelimit;

import com.example.demo.redis.support.LuaScripts;
import com.example.demo.redis.support.RateLimitDecision;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 滑动窗口计数：只保留当前窗口和上一窗口两个计数，按当前窗口已过去的比例加权。
 *
 * <p>内存只有两个数字，适合 QPS 高、能接受少量估算误差的场景。
 * 代价是「估算」：假定上一窗口的请求均匀分布，实际集中在前半段或后半段时会有偏差。</p>
 *
 * <p>相比固定窗口，它消除了窗口交界双倍放行的问题；相比滑动日志，内存从 O(请求数)
 * 降到 O(1)。</p>
 */
@Component
public class SlidingWindowCounterRateLimiter extends AbstractLuaRateLimiter {

    /** 注入共享模板；只用一个 HASH 保存当前和上一窗口的计数。 */
    public SlidingWindowCounterRateLimiter(StringRedisTemplate redis) {
        super(redis, LuaScripts.SLIDING_WINDOW_COUNTER);
    }

    /** URL 中的算法名，也是 Redis key 的一部分。 */
    @Override
    public String name() {
        return "sliding_counter";
    }

    /**
     * 用「上一窗口计数 × 剩余时间比例 + 当前窗口计数」估算最近一个窗口的请求量。
     *
     * @param key           业务维度，同一 key 共享额度
     * @param limit         窗口内允许的请求数
     * @param windowSeconds 窗口长度（秒）
     * @return 放行结果；remaining 为估算后的剩余额度，可能比真实值保守
     */
    @Override
    public RateLimitDecision tryAcquire(String key, long limit, long windowSeconds) {
        String redisKey = redisKey(key);
        long now = System.currentTimeMillis();
        // 脚本内部按 windowMs 计算当前/上一窗口编号，外部只需传毫秒和额度
        String raw = run(redisKey, now, windowSeconds * 1000, limit);
        return decision(key, limit, windowSeconds, redisKey, raw);
    }

    @Override
    protected String describe() {
        return "上一窗口计数按剩余比例衰减 + 当前窗口计数，估算滑动窗口";
    }
}
