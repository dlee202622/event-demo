package com.example.demo.redis;

import com.example.demo.redis.lock.SimpleRedisLock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
class SimpleRedisLockIntegrationTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    static LettuceConnectionFactory factory;
    static SimpleRedisLock lock;

    @BeforeAll
    static void setUp() {
        factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory.afterPropertiesSet();
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        redis.getConnectionFactory().getConnection().serverCommands().flushDb();
        lock = new SimpleRedisLock(redis);
    }

    @AfterAll
    static void tearDown() {
        factory.destroy();
    }

    @Test
    void onlyHolderCanRenewAndRelease() {
        String key = "lock:" + UUID.randomUUID();

        assertTrue(lock.tryLock(key, "A", 5000));
        assertFalse(lock.tryLock(key, "B", 5000), "锁被 A 持有时 B 不能获取");

        assertFalse(lock.renew(key, "B", 5000), "B 不能续别人的锁");
        assertTrue(lock.renew(key, "A", 5000));

        assertFalse(lock.unlock(key, "B"), "B 不能删别人的锁");
        assertFalse(lock.tryLock(key, "B", 5000));

        assertTrue(lock.unlock(key, "A"));
        assertTrue(lock.tryLock(key, "B", 5000), "A 释放后 B 可以获取");
        assertTrue(lock.unlock(key, "B"));
    }

    @Test
    void expiredLockCanBeTakenOverByAnotherHolder() throws InterruptedException {
        String key = "lock:" + UUID.randomUUID();

        assertTrue(lock.tryLock(key, "A", 200));
        Thread.sleep(400);

        assertTrue(lock.tryLock(key, "B", 5000), "A 的锁过期后 B 应该能拿到");
        assertFalse(lock.unlock(key, "A"), "A 已经无权释放 B 的锁");
        assertTrue(lock.unlock(key, "B"));
    }
}
