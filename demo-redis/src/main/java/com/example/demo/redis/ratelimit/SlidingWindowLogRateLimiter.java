package com.example.demo.redis.ratelimit;

import com.example.demo.redis.support.LuaScripts;
import com.example.demo.redis.support.RateLimitDecision;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 滑动窗口日志：ZSET 记录每次请求的时间戳，先裁掉窗口外的，再数窗口内的。
 *
 * <p>最精确的滑动窗口：任意时刻都严格满足「最近 window 秒内不超过 limit 次」。
 * 适合额度小、精度要求高的场景，例如登录失败限制、短信验证码。</p>
 *
 * <p>代价是每个请求一条 ZSET 记录，内存与 QPS × 窗口长度成正比；
 * 高 QPS 长窗口下要慎用，或改用分桶/双窗口计数。</p>
 */
@Component
public class SlidingWindowLogRateLimiter extends AbstractLuaRateLimiter {

    /** 注入共享模板；滑动日志用 ZSET，脚本在父类构造时准备。 */
    public SlidingWindowLogRateLimiter(StringRedisTemplate redis) {
        super(redis, LuaScripts.SLIDING_WINDOW_LOG);
    }

    /** URL 中的算法名，也是 Redis key 的一部分。 */
    @Override
    public String name() {
        return "sliding_log";
    }

    /**
     * 每次请求写入一个 ZSET 成员，score 是当前毫秒时间戳。
     *
     * @param key           业务维度，同一 key 共享额度
     * @param limit         窗口内允许的请求数
     * @param windowSeconds 窗口长度（秒）
     * @return 放行结果；被拒绝时 retryAfterMs 为最早一条请求滑出窗口的等待时间
     */
    @Override
    public RateLimitDecision tryAcquire(String key, long limit, long windowSeconds) {
        String redisKey = redisKey(key);
        long now = System.currentTimeMillis();
        // 同一毫秒可能有多个请求：时间戳相同会被 ZSET 当成同一个 member，必须加 UUID 区分
        String member = now + ":" + UUID.randomUUID();
        String raw = run(redisKey, now, windowSeconds * 1000, limit, member);
        return decision(key, limit, windowSeconds, redisKey, raw);
    }

    @Override
    protected String describe() {
        return "ZSET 存请求时间戳，精确滑动；代价是每个请求一条记录";
    }
}
