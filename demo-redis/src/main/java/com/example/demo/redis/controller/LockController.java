package com.example.demo.redis.controller;

import com.example.demo.redis.lock.LockResults.CounterResult;
import com.example.demo.redis.lock.LockResults.RedissonLockResult;
import com.example.demo.redis.lock.LockResults.SimpleLockResult;
import com.example.demo.redis.lock.RedissonLockService;
import com.example.demo.redis.lock.SimpleLockService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁演示入口。
 *
 * <p>这些接口会真实阻塞一段时间（为了观察 TTL 和续期），默认参数控制在 15 秒内。
 * 每个接口返回事件时间线 + TTL 采样，前端据此画“谁在什么时候拿到锁”。</p>
 *
 * <p>共同参数 {@code key} 只是 key 后缀，实际 Redis key 会带 {@code demo:lock:simple:}
 * 或 {@code demo:lock:redisson:} 前缀，两套实验互不干扰。</p>
 */
@RestController
@RequestMapping("/api/redis/lock")
public class LockController {

    private static final String SIMPLE_PREFIX = "demo:lock:simple:";
    private static final String REDISSON_PREFIX = "demo:lock:redisson:";

    private final SimpleLockService simpleLockService;
    private final RedissonLockService redissonLockService;
    private final StringRedisTemplate redis;

    /** 注入两套锁实验和模板；模板用于 state/cleanup 直接查看和清理 key。 */
    public LockController(SimpleLockService simpleLockService,
                          RedissonLockService redissonLockService,
                          StringRedisTemplate redis) {
        this.simpleLockService = simpleLockService;
        this.redissonLockService = redissonLockService;
        this.redis = redis;
    }

    /**
     * 手写锁：SET NX PX 拿锁，Lua 校验持有者后释放。
     *
     * <p>watchdog=false 且 holdMs &gt; ttlMs 时，B 会在 A 执行中途拿到锁，overlap=true；
     * watchdog=true 时 A 持续续期，B 抢不到，直到 A 主动释放。</p>
     *
     * @param ttlMs   锁 TTL（毫秒），夹在 500–30000
     * @param holdMs  A 的模拟业务耗时（毫秒），同样夹在 500–30000
     */
    @GetMapping("/simple")
    public SimpleLockResult simple(@RequestParam(defaultValue = "demo") String key,
                                   @RequestParam(defaultValue = "3000") long ttlMs,
                                   @RequestParam(defaultValue = "6000") long holdMs,
                                   @RequestParam(defaultValue = "false") boolean watchdog) {
        // 限制范围：太短会让调度抖动主导结果，太长会让 HTTP 请求超时
        long ttl = clamp(ttlMs, 500, 30_000);
        long hold = clamp(holdMs, 500, 30_000);
        return simpleLockService.demo(key, ttl, hold, watchdog);
    }

    /**
     * Redisson 锁：不传 leaseTime，看门狗每 10 秒把 30 秒的锁续回来。
     *
     * <p>预期：TTL 采样在约 20–30 秒之间来回跳，B 在 A 结束前抢不到，
     * {@code watchdogRenewed=true}、{@code overlap=false}。</p>
     */
    @GetMapping("/redisson")
    public RedissonLockResult redisson(@RequestParam(defaultValue = "demo") String key,
                                       @RequestParam(defaultValue = "12") long holdSeconds) {
        return redissonLockService.hold(key, clamp(holdSeconds, 5, 60), null);
    }

    /**
     * Redisson 锁：显式 leaseTime 后看门狗不生效，锁会在业务结束前过期。
     *
     * <p>预期：B 在 leaseSeconds+1 时拿到锁，此时 A 还在执行，{@code overlap=true}，
     * A 最终 unlock 会抛 IllegalMonitorStateException（被服务层捕获成事件）。</p>
     */
    @GetMapping("/redisson-lease")
    public RedissonLockResult redissonLease(@RequestParam(defaultValue = "demo") String key,
                                            @RequestParam(defaultValue = "3") long leaseSeconds,
                                            @RequestParam(defaultValue = "6") long holdSeconds) {
        long lease = clamp(leaseSeconds, 1, 60);
        long hold = clamp(holdSeconds, 2, 60);
        if (hold <= lease) {
            throw new IllegalArgumentException("holdSeconds 必须大于 leaseSeconds，才能看到锁提前过期");
        }
        return redissonLockService.hold(key, hold, lease);
    }

    /**
     * 并发计数：不加锁会丢更新，加 Redisson 锁后结果精确。
     *
     * <p>默认 10 线程 × 20 次、每次读改写之间 sleep 1ms：不加锁时 actual 通常只有几十；
     * 加锁后 actual == expected == 200。</p>
     */
    @GetMapping("/counter")
    public CounterResult counter(@RequestParam(defaultValue = "false") boolean locked,
                                 @RequestParam(defaultValue = "10") int threads,
                                 @RequestParam(defaultValue = "20") int iterations) {
        // 上限保护：线程/次数过大时，即使有锁也会让请求跑很久
        return redissonLockService.counter(locked, (int) clamp(threads, 1, 50), (int) clamp(iterations, 1, 200));
    }

    /** 查看锁 key 的当前持有者和剩余 TTL；两套实验的 key 一起返回，便于对照。 */
    @GetMapping("/state")
    public Map<String, Object> state(@RequestParam(defaultValue = "demo") String key) {
        Map<String, Object> state = new LinkedHashMap<>();
        for (String prefix : List.of(SIMPLE_PREFIX, REDISSON_PREFIX)) {
            String redisKey = prefix + key;
            // holder=null 表示 key 不存在；ttlMs=-2 表示 key 不存在，-1 表示无过期时间
            state.put(redisKey, Map.of(
                    "holder", String.valueOf(redis.opsForValue().get(redisKey)),
                    "ttlMs", redis.getExpire(redisKey, TimeUnit.MILLISECONDS)));
        }
        return state;
    }

    /** 清理演示锁，避免上一轮异常退出留下脏锁影响下一次实验。 */
    @DeleteMapping
    public Map<String, Object> cleanup(@RequestParam(defaultValue = "demo") String key) {
        Long deleted = redis.delete(List.of(SIMPLE_PREFIX + key, REDISSON_PREFIX + key));
        return Map.of("deleted", deleted);
    }

    /** 把实验参数夹到安全范围，保证一次请求不会因为极端参数而长时间挂起。 */
    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
