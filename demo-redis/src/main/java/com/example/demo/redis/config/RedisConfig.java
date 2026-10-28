package com.example.demo.redis.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Redisson 配置：只用来演示分布式锁（可重入 + 看门狗）。
 *
 * <p>普通读写走 Spring Data Redis（Lettuce），Redisson 单独建连接池，
 * 避免为了一个锁把整个客户端换掉。</p>
 */
@Configuration
public class RedisConfig {

    /**
     * 创建 Redisson 客户端，被 {@code RedissonLockService} 用于可重入锁与看门狗。
     *
     * <p>地址、库号、密码直接复用 {@code spring.data.redis.*}，保证和 Lettuce 连的是同一个 Redis。
     * {@code lockWatchdogTimeout} 是整个客户端级别的参数：所有没显式传 leaseTime 的锁都按它算
     * TTL，并由看门狗以 TTL/3 的间隔续期。</p>
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.database:0}") int database,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${demo.redis.lock.watchdog-timeout-ms:30000}") long watchdogTimeoutMs) {

        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(8);
        if (StringUtils.hasText(password)) {
            config.useSingleServer().setPassword(password);
        }
        // 看门狗超时：锁默认 30s 过期，后台每 1/3（10s）续期一次
        config.setLockWatchdogTimeout(watchdogTimeoutMs);
        return Redisson.create(config);
    }
}
