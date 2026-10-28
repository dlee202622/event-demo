package com.example.demo.redis.support;

/**
 * 一次限流判定的结果。retryAfterMs 表示最早多久之后可能被放行。
 */
public record RateLimitDecision(String algorithm, String key, boolean allowed,
                                long limit, long remaining, long windowSeconds,
                                long retryAfterMs, String redisKey, String detail) {
}
