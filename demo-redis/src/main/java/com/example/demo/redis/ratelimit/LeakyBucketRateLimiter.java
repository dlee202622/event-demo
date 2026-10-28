package com.example.demo.redis.ratelimit;

import com.example.demo.redis.support.LuaScripts;
import com.example.demo.redis.support.RateLimitDecision;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 漏桶：请求先进入桶中排队，再以固定速率漏出，桶满拒绝。
 *
 * <p>输出速率恒定，适合保护下游：无论上游多突发，下游看到的都是平滑流量。
 * 代价是突发流量会被拒绝（本实现不排队等待），且桶容量决定了能容忍多少瞬时堆积。</p>
 *
 * <p>实现上只维护「当前水位」：每次请求先按经过时间漏水，再尝试加水；
 * 水位满则拒绝，并告诉调用方多久后能腾出空间。</p>
 */
@Component
public class LeakyBucketRateLimiter extends AbstractLuaRateLimiter {

    /** 注入共享模板；水位和时间戳都落在 Redis，实例重启不会丢失状态。 */
    public LeakyBucketRateLimiter(StringRedisTemplate redis) {
        super(redis, LuaScripts.LEAKY_BUCKET);
    }

    /** URL 中的算法名，也是 Redis key 的一部分。 */
    @Override
    public String name() {
        return "leaky_bucket";
    }

    /**
     * 水位按固定速率下降，只有「水位 + 1 ≤ 容量」时才接收请求。
     *
     * @param key           业务维度，同一 key 共享桶
     * @param limit         桶容量，即允许的最大瞬时堆积量
     * @param windowSeconds 漏完一桶所需时间（秒），决定输出速率
     * @return 放行结果；被拒绝时 retryAfterMs 为水位降到可再容纳一个请求的时间
     */
    @Override
    public RateLimitDecision tryAcquire(String key, long limit, long windowSeconds) {
        String redisKey = redisKey(key);
        long now = System.currentTimeMillis();
        // 每秒漏出 limit/window；下限 0.001 防止 Lua 里除以 0
        String leakPerSecond = String.format(Locale.ROOT, "%.4f",
                Math.max(limit / (double) windowSeconds, 0.001));
        // 参数顺序：now、容量、每秒漏出数、窗口毫秒（仅用于 TTL）
        String raw = run(redisKey, now, limit, leakPerSecond, windowSeconds * 1000);
        return decision(key, limit, windowSeconds, redisKey, raw);
    }

    @Override
    protected String describe() {
        return "容量 = limit，每秒漏出 limit/window；输出平滑，突发优先被拒";
    }
}
