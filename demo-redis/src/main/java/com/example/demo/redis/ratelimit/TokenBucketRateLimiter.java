package com.example.demo.redis.ratelimit;

import com.example.demo.redis.support.LuaScripts;
import com.example.demo.redis.support.RateLimitDecision;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 令牌桶：桶容量 limit，按 window 时间均匀补令牌。
 *
 * <p>允许短时突发（最多一次用掉整个桶），同时把长期平均速率压在 limit / window 上。
 * 适合需要「允许突发、但长期不超限」的接口，例如开放平台的配额。</p>
 *
 * <p>与漏桶的区别：令牌桶关注「能否拿到令牌」，空闲时攒下的令牌可以应对突发；
 * 漏桶关注「输出多平滑」，桶满后直接拒绝。</p>
 */
@Component
public class TokenBucketRateLimiter extends AbstractLuaRateLimiter {

    /** 注入共享模板；令牌数不存 Java 内存，全部状态都在 Redis 的 HASH 里。 */
    public TokenBucketRateLimiter(StringRedisTemplate redis) {
        super(redis, LuaScripts.TOKEN_BUCKET);
    }

    /** URL 中的算法名，也是 Redis key 的一部分。 */
    @Override
    public String name() {
        return "token_bucket";
    }

    /**
     * 按经过时间惰性补充令牌，拿到 1 个才放行。
     *
     * @param key           业务维度，同一 key 共享桶
     * @param limit         桶容量，也是允许的最大突发量
     * @param windowSeconds 补充完一桶所需时间（秒）
     * @return 放行结果；被拒绝时 retryAfterMs 为攒够 1 个令牌的等待时间
     */
    @Override
    public RateLimitDecision tryAcquire(String key, long limit, long windowSeconds) {
        String redisKey = redisKey(key);
        long now = System.currentTimeMillis();
        // 把「每 window 秒补充 limit 个」换算成每秒补充数；下限 0.001 防止脚本里除零
        String refillPerSecond = String.format(Locale.ROOT, "%.4f",
                Math.max(limit / (double) windowSeconds, 0.001));
        // 参数顺序：now、容量、每秒补充数、窗口毫秒（仅用于 TTL）
        String raw = run(redisKey, now, limit, refillPerSecond, windowSeconds * 1000);
        return decision(key, limit, windowSeconds, redisKey, raw);
    }

    @Override
    protected String describe() {
        return "容量 = limit，每秒补 limit/window 个令牌；允许突发、长期平均受限";
    }
}
