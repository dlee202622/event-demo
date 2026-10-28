package com.example.demo.redis.lock;

import com.example.demo.redis.support.LuaScripts;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 手写分布式锁：SET NX PX + Lua 释放/续期。
 *
 * <p>三个关键点：</p>
 * <ol>
 *   <li>加锁必须是一个原子命令（SET key holder NX PX ttl），不能 GET 再 SET；</li>
 *   <li>value 用唯一持有者标识，释放/续期前先校验，避免删掉别人的锁；</li>
 *   <li>TTL 是兜底：进程崩溃后锁最终会过期，不会永久死锁。</li>
 * </ol>
 *
 * <p>这里刻意不加自动续期，用来观察「业务执行时间超过锁 TTL」会发生什么；
 * 需要续期时由 {@link SimpleLockService} 起一个看门狗调用 {@link #renew}。</p>
 *
 * <p>限制：单机 Redis 主从切换可能丢锁；释放/续期只能保证不误删，
 * 不能阻止「锁过期后旧持有者继续写下游」——那需要 fencing token 或下游幂等。</p>
 */
@Component
public class SimpleRedisLock {

    private final StringRedisTemplate redis;

    /** 释放脚本：GET 比较持有者 + DEL 在同一脚本内完成，消除判断与删除之间的空窗。 */
    private final DefaultRedisScript<Long> releaseScript =
            new DefaultRedisScript<>(LuaScripts.LOCK_RELEASE, Long.class);
    /** 续期脚本：只有仍持有锁时才 PEXPIRE，锁过期或被抢占时返回 0。 */
    private final DefaultRedisScript<Long> renewScript =
            new DefaultRedisScript<>(LuaScripts.LOCK_RENEW, Long.class);

    /** 注入 StringRedisTemplate；脚本对象在构造时创建一次，后续复用。 */
    public SimpleRedisLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 尝试加锁，不等待：抢不到立即返回 false。
     *
     * <p>{@code setIfAbsent(key, holder, Duration)} 对应 {@code SET key holder NX PX ttl}，
     * 是一个原子命令，因此不会出现两个客户端同时认为自己加锁成功。</p>
     *
     * @param key       锁的 Redis key
     * @param holder    持有者唯一标识（建议 UUID+线程名），释放/续期时用它校验
     * @param ttlMillis 锁的存活时间（毫秒），必须大于一次业务执行时间
     * @return true=加锁成功；false=锁已被占用
     */
    public boolean tryLock(String key, String holder, long ttlMillis) {
        return Boolean.TRUE.equals(redis.opsForValue()
                .setIfAbsent(key, holder, Duration.ofMillis(ttlMillis)));
    }

    /**
     * 只有仍是自己持有时才续期，返回是否续期成功。
     *
     * <p>返回 false 说明锁已经过期或被别人拿走，业务不应再继续执行受保护的写操作。</p>
     */
    public boolean renew(String key, String holder, long ttlMillis) {
        Long result = redis.execute(renewScript, List.of(key), holder, String.valueOf(ttlMillis));
        return result != null && result == 1L;
    }

    /**
     * 只有仍是自己持有时才删除，避免误删别人的锁。
     *
     * <p>如果拆成 GET + DEL：A 判断值相等后锁恰好过期，B 拿到锁，A 的 DEL 会删掉 B 的锁。</p>
     */
    public boolean unlock(String key, String holder) {
        Long result = redis.execute(releaseScript, List.of(key), holder);
        return result != null && result == 1L;
    }
}
