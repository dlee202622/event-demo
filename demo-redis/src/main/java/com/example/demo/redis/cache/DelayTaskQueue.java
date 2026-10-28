package com.example.demo.redis.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 基于 ZSET 的延迟队列：score 存到期时间戳，轮询取出 score &lt;= now 的任务。
 *
 * <p>用 ZSET 是因为它天然按时间排序，{@code ZRANGEBYSCORE 0 now} 就是「所有到期任务」。
 * member 用 cacheKey，同一个商品重复登记会更新 score 而不是产生重复任务。</p>
 *
 * <p>多实例安全：先 {@code ZREM} 再执行，只有一个实例能删除成功（返回 1），
 * 从而避免重复消费。代价是「删了但执行失败」会丢任务；本演示选择 at-most-once，
 * 生产环境应改用 Redis Stream / MQ 的 ACK 机制，或增加重试表。</p>
 *
 * <p>执行历史只放在进程内的 {@link ConcurrentLinkedDeque}，用于页面观察，重启即丢失。</p>
 */
@Component
public class DelayTaskQueue {

    private static final Logger log = LoggerFactory.getLogger(DelayTaskQueue.class);
    /** 所有延迟删除共用一个 ZSET；member 是缓存 key，score 是到期毫秒时间戳。 */
    public static final String QUEUE_KEY = "demo:delay:cache-delete";

    private final StringRedisTemplate redis;
    // 只保留最近 20 条执行记录，避免演示进程长期运行导致内存增长
    private final ConcurrentLinkedDeque<ExecutedTask> executed = new ConcurrentLinkedDeque<>();

    /** 注入 StringRedisTemplate；队列本身无本地状态，多实例共享同一个 ZSET。 */
    public DelayTaskQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 登记一个延迟任务，返回到期时间戳。
     *
     * <p>同一个 cacheKey 重复登记时，ZADD 会更新 score（延后到新的到期时间），
     * 这正好符合「最后一次写获胜」的语义，不会堆积重复删除。</p>
     *
     * @param cacheKey    到点后要删除的缓存 key
     * @param delayMillis 延迟毫秒数，必须大于一次读回填的窗口
     * @return 到期时间戳（epoch 毫秒）
     */
    public long schedule(String cacheKey, long delayMillis) {
        long dueAt = System.currentTimeMillis() + delayMillis;
        redis.opsForZSet().add(QUEUE_KEY, cacheKey, dueAt);
        return dueAt;
    }

    /**
     * 每 200ms 轮询一次到期任务。
     *
     * <p>fixedDelay 表示「上一次执行结束后再等 200ms」，不会因任务变慢而重叠执行；
     * 单次最多取 20 条，避免一次清理太多 key 阻塞 Redis。</p>
     */
    @Scheduled(fixedDelay = 200, initialDelay = 200)
    public void poll() {
        long now = System.currentTimeMillis();
        Set<String> due = redis.opsForZSet().rangeByScore(QUEUE_KEY, 0, now, 0, 20);
        if (due == null || due.isEmpty()) {
            return;
        }
        for (String cacheKey : due) {
            // ZREM 的返回值是「是否真的删掉了」：多实例并发时只有一个实例拿到 1
            Long removed = redis.opsForZSet().remove(QUEUE_KEY, cacheKey);
            if (removed != null && removed > 0) {
                // 删除缓存失败不会回滚 ZREM，因此这里是 at-most-once；
                // 对缓存删除来说可接受，最坏情况是脏数据留到 TTL 过期
                redis.delete(cacheKey);
                executed.addFirst(new ExecutedTask(now, cacheKey));
                while (executed.size() > 20) {
                    executed.removeLast();
                }
                log.info("[delay-queue] 执行二次删除: {}", cacheKey);
            }
        }
    }

    /** 查看尚未执行的任务：到期时间戳与剩余毫秒，用于演示页面和调试。 */
    public List<Map<String, Object>> pending() {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().rangeWithScores(QUEUE_KEY, 0, -1);
        List<Map<String, Object>> result = new ArrayList<>();
        if (tuples != null) {
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("cacheKey", tuple.getValue());
                // score 是 double，转 long 得到毫秒时间戳；null 理论上不会出现，防御性处理
                item.put("dueAt", tuple.getScore() == null ? null : tuple.getScore().longValue());
                // 已到期的任务 remainingMs 归零，等待下一次 poll 清理
                item.put("remainingMs", tuple.getScore() == null
                        ? null : Math.max(0, tuple.getScore().longValue() - System.currentTimeMillis()));
                result.add(item);
            }
        }
        return result;
    }

    /** 最近执行过的任务快照（最多 20 条），最新在前。 */
    public List<ExecutedTask> executed() {
        return List.copyOf(executed);
    }

    /** @param atMillis 执行时刻（epoch 毫秒）；cacheKey 被删除的缓存 key */
    public record ExecutedTask(long atMillis, String cacheKey) {
    }
}
