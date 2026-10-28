package com.example.demo.redis;

import com.example.demo.redis.ratelimit.FixedWindowRateLimiter;
import com.example.demo.redis.ratelimit.LeakyBucketRateLimiter;
import com.example.demo.redis.ratelimit.RateLimiter;
import com.example.demo.redis.ratelimit.SlidingWindowBucketRateLimiter;
import com.example.demo.redis.ratelimit.SlidingWindowCounterRateLimiter;
import com.example.demo.redis.ratelimit.SlidingWindowLogRateLimiter;
import com.example.demo.redis.ratelimit.TokenBucketRateLimiter;
import com.example.demo.redis.support.RateLimitDecision;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 六种限流器都需要真实 Lua 执行环境，用 Testcontainers 起一个一次性 Redis。
 */
@Testcontainers
class RateLimiterIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;

    @BeforeAll
    static void setUp() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @AfterAll
    static void tearDown() {
        factory.destroy();
    }

    static Stream<Arguments> limiters() {
        return Stream.of(
                Arguments.of(new FixedWindowRateLimiter(redis)),
                Arguments.of(new SlidingWindowLogRateLimiter(redis)),
                Arguments.of(new SlidingWindowCounterRateLimiter(redis)),
                Arguments.of(new SlidingWindowBucketRateLimiter(redis)),
                Arguments.of(new TokenBucketRateLimiter(redis)),
                Arguments.of(new LeakyBucketRateLimiter(redis)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("limiters")
    void allowsExactlyLimitThenDenies(RateLimiter limiter) {
        String key = "test-" + UUID.randomUUID();
        long limit = 5;

        for (int i = 0; i < limit; i++) {
            RateLimitDecision decision = limiter.tryAcquire(key, limit, 10);
            assertTrue(decision.allowed(), limiter.name() + " 第 " + (i + 1) + " 次应放行");
            assertEquals(limit - i - 1, decision.remaining());
        }

        RateLimitDecision denied = limiter.tryAcquire(key, limit, 10);
        assertFalse(denied.allowed(), limiter.name() + " 超额后应拒绝");
        assertEquals(0, denied.remaining());
        assertTrue(denied.retryAfterMs() > 0, "拒绝时应给出重试等待时间");
    }

    @Test
    void stateExposesUnderlyingRedisStructure() {
        RateLimiter log = new SlidingWindowLogRateLimiter(redis);
        String key = "state-" + UUID.randomUUID();
        log.tryAcquire(key, 5, 10);
        assertEquals("zset", log.state(key).get("type"));

        RateLimiter token = new TokenBucketRateLimiter(redis);
        String tokenKey = "state-" + UUID.randomUUID();
        token.tryAcquire(tokenKey, 5, 10);
        assertEquals("hash", token.state(tokenKey).get("type"));
    }
}
