package com.example.demo.redis.ratelimit;

import com.example.demo.redis.support.LuaScripts;
import com.example.demo.redis.support.RateLimitDecision;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 固定窗口：每个窗口一个计数器，窗口边界一到就归零。
 *
 * <p>实现最简单、内存最省：一个 Hash，每个窗口一个字段。适合对边界精度要求不高的
 * 粗粒度保护，例如「每个 API key 每分钟 1000 次」。</p>
 *
 * <p>缺点：窗口交界处可能放过接近 2 倍流量。例如限制 5 次/10 秒，在第 9 秒发 5 次、
 * 第 11 秒再发 5 次，两批都能通过。需要平滑时改用滑动窗口或令牌桶。</p>
 */
@Component
public class FixedWindowRateLimiter extends AbstractLuaRateLimiter {

    /** 注入共享模板；父类会把脚本编译成可复用的 DefaultRedisScript。 */
    public FixedWindowRateLimiter(StringRedisTemplate redis) {
        super(redis, LuaScripts.FIXED_WINDOW);
    }

    /** URL 中的算法名，也是 Redis key 的一部分（demo:rl:fixed_window:...）。 */
    @Override
    public String name() {
        return "fixed_window";
    }

    /**
     * 当前时间决定落在哪个窗口；同一窗口内的所有请求共享同一个计数。
     *
     * @param key           业务维度，同一 key 共享额度
     * @param limit         每个窗口允许的请求数
     * @param windowSeconds 窗口长度（秒）
     * @return 放行结果；被拒绝时 retryAfterMs 为距本窗口结束的时间
     */
    @Override
    public RateLimitDecision tryAcquire(String key, long limit, long windowSeconds) {
        String redisKey = redisKey(key);
        // 秒转毫秒：Lua 里的窗口编号、重试时间都以毫秒计算
        String raw = run(redisKey, System.currentTimeMillis(), windowSeconds * 1000, limit);
        return decision(key, limit, windowSeconds, redisKey, raw);
    }

    @Override
    protected String describe() {
        return "按窗口编号对齐计数，窗口交界处可能双倍放行";
    }
}
