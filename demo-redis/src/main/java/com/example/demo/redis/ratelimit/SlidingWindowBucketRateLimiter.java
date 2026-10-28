package com.example.demo.redis.ratelimit;

import com.example.demo.redis.support.LuaScripts;
import com.example.demo.redis.support.RateLimitDecision;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 滑动窗口分桶：把窗口切成固定数量的小桶，每次累加最近 N 个桶。
 *
 * <p>精度介于「滑动日志」和「双窗口计数」之间：桶越多越接近精确滑动，
 * 内存和每次判定的 HGET 次数也越多。</p>
 *
 * <p>相比双窗口计数，它不假设请求均匀分布，而是按桶记录真实数量；
 * 相比滑动日志，内存上限是桶数而不是请求数。</p>
 */
@Component
public class SlidingWindowBucketRateLimiter extends AbstractLuaRateLimiter {

    /** 一个窗口切成 10 个桶，例如 10s 窗口每桶 1s；精度约为窗口的 1/10。 */
    private static final int BUCKETS = 10;

    /** 注入共享模板；桶数量在构造前已由常量确定，脚本运行期只负责累加。 */
    public SlidingWindowBucketRateLimiter(StringRedisTemplate redis) {
        super(redis, LuaScripts.SLIDING_WINDOW_BUCKET);
    }

    /** URL 中的算法名，也是 Redis key 的一部分。 */
    @Override
    public String name() {
        return "sliding_bucket";
    }

    /**
     * 累加最近 BUCKETS 个桶的计数，超过 limit 就拒绝。
     *
     * @param key           业务维度，同一 key 共享额度
     * @param limit         窗口内允许的请求数
     * @param windowSeconds 窗口长度（秒）
     * @return 放行结果；被拒绝时 retryAfterMs 为最早能让出名额的桶边界
     */
    @Override
    public RateLimitDecision tryAcquire(String key, long limit, long windowSeconds) {
        String redisKey = redisKey(key);
        long now = System.currentTimeMillis();
        // 桶宽 = windowMs / BUCKETS，由脚本计算；这里只传毫秒窗口和桶数
        String raw = run(redisKey, now, windowSeconds * 1000, limit, BUCKETS);
        return decision(key, limit, windowSeconds, redisKey, raw);
    }

    @Override
    protected String describe() {
        return "HASH 每 " + BUCKETS + " 分之一窗口一个桶，累加最近 " + BUCKETS + " 个桶";
    }
}
