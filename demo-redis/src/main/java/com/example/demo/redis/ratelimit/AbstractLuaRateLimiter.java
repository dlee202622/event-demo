package com.example.demo.redis.ratelimit;

import com.example.demo.redis.support.RateLimitDecision;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 限流的公共部分：执行 Lua、解析结果、查看底层结构。
 *
 * <p>六个算法只在「Lua 脚本 + 参数」上不同，其余流程完全一致，因此抽到这个基类：
 * 子类负责把业务参数换算成脚本参数，父类负责原子执行和结果解析。</p>
 *
 * <p>Lua 统一返回 {@code 允许|剩余|重试毫秒}，避免不同客户端对数组类型的映射差异。</p>
 */
public abstract class AbstractLuaRateLimiter implements RateLimiter {

    /** 所有脚本都通过 StringRedisTemplate 执行，保证 key 和参数按 UTF-8 字符串编码。 */
    protected final StringRedisTemplate redis;
    private final DefaultRedisScript<String> script;

    /**
     * @param redis 已配置 StringRedisSerializer 的模板
     * @param lua   算法对应的脚本；返回类型固定为 String
     */
    protected AbstractLuaRateLimiter(StringRedisTemplate redis, String lua) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>(lua, String.class);
    }

    /**
     * 执行脚本。Spring Data Redis 会自动使用 EVALSHA 并在脚本未缓存时回退到 EVAL。
     *
     * @param redisKey 算法自己的 key，作为 KEYS[1]
     * @param args     脚本参数，按顺序对应 ARGV[1..n]
     * @return 脚本原始返回串，格式由各算法约定
     */
    protected String run(String redisKey, Object... args) {
        // StringRedisTemplate 用 StringRedisSerializer 序列化参数，数字必须先转字符串；
        // 直接传 Long 会被当成其他类型序列化，脚本里的 tonumber(ARGV[n]) 可能拿到 nil
        Object[] stringArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            stringArgs[i] = String.valueOf(args[i]);
        }
        return redis.execute(script, List.of(redisKey), stringArgs);
    }

    /**
     * key 里带算法名：同一个业务 key（如 user-1）在不同算法下互不干扰，
     * 这样对比实验可以并行跑而不会共用计数。
     */
    protected String redisKey(String key) {
        return "demo:rl:" + name() + ":" + key;
    }

    /**
     * 把 {@code 允许|剩余|重试毫秒} 解析成对外的统一结果。
     *
     * <p>剩余额度在估算类算法（滑动计数）里可能是负数或小数，这里统一截断到 ≥0，
     * 调用方不需要理解每种算法的内部表示。</p>
     */
    protected RateLimitDecision decision(String key, long limit, long windowSeconds,
                                         String redisKey, String raw) {
        String[] parts = raw.split("\\|");
        boolean allowed = "1".equals(parts[0]);
        long remaining = Math.max(0, Long.parseLong(parts[1]));
        long retryAfterMs = Math.max(0, Long.parseLong(parts[2]));
        return new RateLimitDecision(name(), key, allowed, limit, remaining, windowSeconds,
                retryAfterMs, redisKey, describe());
    }

    /** 这个算法一句话说明，出现在接口返回里。 */
    protected abstract String describe();

    /** 把子类的算法说明暴露给接口层，调用方不需要知道具体实现类。 */
    @Override
    public String description() {
        return describe();
    }

    /**
     * 直接读取算法在 Redis 里的原始结构，用于教学观察：
     * 固定窗口是 HASH，滑动日志是 ZSET，令牌桶/漏桶是 HASH。
     *
     * <p>TTL 的返回语义：-1 表示 key 存在但无过期时间，-2 表示 key 不存在。</p>
     */
    @Override
    public Map<String, Object> state(String key) {
        String redisKey = redisKey(key);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("redisKey", redisKey);
        DataType type = redis.type(redisKey);
        state.put("type", type.code());
        state.put("ttlMs", redis.getExpire(redisKey, TimeUnit.MILLISECONDS));
        // 不同算法落在不同结构上，按类型读取，避免对 String 执行 ZSET 命令报错
        switch (type) {
            case STRING -> state.put("value", redis.opsForValue().get(redisKey));
            case HASH -> state.put("value", redis.opsForHash().entries(redisKey));
            case ZSET -> state.put("value", redis.opsForZSet().rangeWithScores(redisKey, 0, -1));
            case LIST -> state.put("value", redis.opsForList().range(redisKey, 0, -1));
            case SET -> state.put("value", redis.opsForSet().members(redisKey));
            default -> state.put("value", null);
        }
        return state;
    }
}
