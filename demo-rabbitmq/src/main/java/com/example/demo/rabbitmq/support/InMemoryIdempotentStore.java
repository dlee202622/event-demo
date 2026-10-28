package com.example.demo.rabbitmq.support;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内消息去重登记表，用于演示消费端幂等。
 *
 * <p>这个实现只适合单实例 Demo：记录存在当前 JVM 堆里，应用重启就丢失，
 * 多个实例之间也不共享。生产环境通常换成：</p>
 * <ul>
 *   <li>Redis：{@code SET demo:consumed:{id} 1 NX EX 600}，原子抢占 + 自动过期；</li>
 *   <li>数据库去重表：对业务唯一键建唯一索引，插入冲突即视为重复；</li>
 *   <li>业务表本身的唯一约束（如订单号），把幂等交给数据库。</li>
 * </ul>
 */
@Component
public class InMemoryIdempotentStore {

    /** 同一条消息在 10 分钟内重复投递都视为重复；超过后允许再次处理。 */
    private static final long TTL_MILLIS = Duration.ofMinutes(10).toMillis();

    /** 超过该数量时顺手清理过期记录，避免 Demo 长期运行导致 Map 无限增长。 */
    private static final int CLEANUP_THRESHOLD = 10_000;

    /** key = 场景 + 业务 ID，value = 记录过期的时间戳。 */
    private final ConcurrentHashMap<String, Long> expireAtByKey = new ConcurrentHashMap<>();

    /**
     * 尝试登记一条消息。
     *
     * @return {@code true} 表示首次出现，调用方应执行业务；
     *         {@code false} 表示 TTL 内已经处理过，调用方应直接跳过
     */
    public boolean tryAcquire(String scene, String messageId) {
        String key = scene + ":" + messageId;
        long now = System.currentTimeMillis();
        // compute 对同一个 key 是原子的：并发拿到同一条消息时只有一个线程能看到 null，
        // 其余线程会看到尚未过期的旧时间戳，从而正确识别为重复。
        boolean[] first = {false};
        expireAtByKey.compute(key, (ignored, expireAt) -> {
            if (expireAt == null || expireAt <= now) {
                first[0] = true;
                return now + TTL_MILLIS;
            }
            return expireAt;
        });
        cleanupIfNeeded(now);
        return first[0];
    }

    /**
     * 业务处理失败时撤销登记。
     *
     * <p>必须先抢占再处理，否则并发重复消息会同时通过检查；但抢占成功后如果业务失败，
     * 必须删除记录并把异常抛出去，这样 Broker 重投时才有机会重新处理，
     * 而不是被当成“重复消息”永远跳过。</p>
     */
    public void release(String scene, String messageId) {
        expireAtByKey.remove(scene + ":" + messageId);
    }

    /** 当前登记的 key 数量，仅用于演示和诊断。 */
    public int size() {
        return expireAtByKey.size();
    }

    private void cleanupIfNeeded(long now) {
        if (expireAtByKey.size() <= CLEANUP_THRESHOLD) {
            return;
        }
        expireAtByKey.entrySet().removeIf(entry -> entry.getValue() <= now);
    }
}
