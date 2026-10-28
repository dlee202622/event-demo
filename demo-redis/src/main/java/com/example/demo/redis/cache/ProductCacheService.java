package com.example.demo.redis.cache;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 缓存旁路（cache-aside）+ 延迟双删。
 *
 * <p>读：先查缓存，未命中回源并回填。<br>
 * 写：更新 DB → 删缓存 → 延迟再删一次，覆盖「旧值在删除之后才被回填」的竞态。</p>
 *
 * <p>为什么写路径删除而不是更新缓存：并发写时，后到的旧值可能覆盖先到的新值；
 * 删除让下一次读自然回源，代价是短暂未命中。</p>
 *
 * <p>为什么还要延迟第二次删除：读线程可能在「删缓存之后、写库之前」拿到旧快照，
 * 写库完成后再把旧值回填。延迟删除等这个回填窗口过去，再清一次。</p>
 *
 * <p>局限：延迟双删降低脏读概率，但不是强一致。DB 主从延迟、进程崩溃导致延迟任务丢失、
 * 延迟时间小于实际回填耗时，仍会留下脏数据。强一致要靠串行化/版本号/订阅 binlog。</p>
 */
@Service
public class ProductCacheService {

    /** 缓存 key 前缀；与示例数据类型、限流 key 分开命名空间。 */
    private static final String KEY_PREFIX = "demo:cache:product:";

    private final StringRedisTemplate redis;
    private final ProductRepository repository;
    private final DelayTaskQueue delayQueue;
    private final ObjectMapper objectMapper;
    private final long ttlSeconds;
    private final long delayedDeleteDelayMs;
    private final long defaultSlowReadMs;

    /**
     * @param ttlSeconds              缓存过期时间（秒），兜底清理长期不更新的 key
     * @param delayedDeleteDelayMs    第二次删除的延迟（毫秒），必须大于一次回源+回填的耗时
     * @param defaultSlowReadMs       默认的模拟慢查询耗时（毫秒），0 表示不人为延迟
     */
    public ProductCacheService(StringRedisTemplate redis,
                               ProductRepository repository,
                               DelayTaskQueue delayQueue,
                               ObjectMapper objectMapper,
                               @Value("${demo.redis.cache.ttl-seconds:30}") long ttlSeconds,
                               @Value("${demo.redis.cache.delayed-delete-delay-ms:1000}") long delayedDeleteDelayMs,
                               @Value("${demo.redis.cache.slow-read-ms:0}") long defaultSlowReadMs) {
        this.redis = redis;
        this.repository = repository;
        this.delayQueue = delayQueue;
        this.objectMapper = objectMapper;
        this.ttlSeconds = ttlSeconds;
        this.delayedDeleteDelayMs = delayedDeleteDelayMs;
        this.defaultSlowReadMs = defaultSlowReadMs;
    }

    /** @param source CACHE=命中缓存，DB=回源数据库；elapsedMillis 用于观察缓存收益 */
    public record ReadResult(long id, Product product, String source, String cacheKey, long elapsedMillis) {
    }

    /**
     * @param dbUpdatedAtMillis       写库耗时
     * @param cacheDeletedAtMillis    第一次删除缓存的时间点
     * @param delayedDeleteDueAtMillis 第二次删除的到期时间点
     */
    public record UpdateResult(long id, String cacheKey, long dbUpdatedAtMillis,
                               long cacheDeletedAtMillis, long delayedDeleteDueAtMillis, String note) {
    }

    /** 竞态时间线上的一个事件；cacheValue 是该时刻缓存里的原始 JSON（null 表示已清空）。 */
    public record RaceEvent(long atMillis, String action, String cacheValue) {
    }

    /** @param cacheAfterStaleRefill 旧值回填后的缓存内容，正常情况下应与最终值不同 */
    public record RaceResult(long productId, List<RaceEvent> events,
                             String cacheAfterStaleRefill, String cacheAfterDelayedDelete,
                             Product finalProduct, String finalSource) {
    }

    /** @param ttlMillis Redis TTL：-1=无过期时间，-2=key 不存在 */
    public record InspectResult(long id, String cacheKey, String cached, long ttlMillis, Product database) {
    }

    /**
     * 读路径：缓存优先，未命中回源并回填。
     *
     * @param slowReadMs 非空时覆盖配置，用来人为拉长「回源 → 回填」的窗口；
     *                   0 表示不延迟，便于测量纯缓存/回源耗时
     */
    public ReadResult read(long id, Long slowReadMs) {
        long start = System.currentTimeMillis();
        String cacheKey = cacheKey(id);
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            // 命中：不查库，直接反序列化返回；source=CACHE 便于对比耗时
            return new ReadResult(id, deserialize(cached), "CACHE", cacheKey,
                    System.currentTimeMillis() - start);
        }

        // 模拟慢查询：放在查库之前，等价于「读线程很慢才拿到快照」
        sleep(slowReadMs == null ? defaultSlowReadMs : Math.max(0, slowReadMs));
        Product product = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在: " + id));
        // 回填时设置 TTL：即使后续删除失败，脏数据也会在 ttlSeconds 后自然消失
        redis.opsForValue().set(cacheKey, serialize(product), Duration.ofSeconds(ttlSeconds));
        return new ReadResult(id, product, "DB", cacheKey, System.currentTimeMillis() - start);
    }

    /**
     * 写路径：先更新 DB，再删缓存，最后登记延迟删除。
     *
     * <p>顺序不能反：如果先删缓存再写库，写库完成前的读请求会把旧值回填，
     * 而这次写入没有后续删除来纠正它。</p>
     *
     * @param name  新名称，不能为空
     * @param price 新价格，必须大于 0
     */
    public UpdateResult update(long id, String name, BigDecimal price) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 不能为空");
        }
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("price 必须大于 0");
        }
        long start = System.currentTimeMillis();
        // 先落库：DB 是事实来源；写库失败时缓存保持不变，不会出现「缓存新、DB 旧」
        repository.update(id, name, price);
        long dbDone = System.currentTimeMillis() - start;

        String cacheKey = cacheKey(id);
        // 第一次删除：让后续读请求回源；此时仍可能有读线程持有旧快照
        redis.delete(cacheKey);
        long deleted = System.currentTimeMillis() - start;

        // 第二次删除：延迟到旧快照大概率已回填之后再执行
        long dueAt = delayQueue.schedule(cacheKey, delayedDeleteDelayMs);
        return new UpdateResult(id, cacheKey, dbDone, deleted, dueAt - start,
                "更新 DB → 删除缓存 → " + delayedDeleteDelayMs + "ms 后再次删除");
    }

    /**
     * 确定性复现脏读窗口：
     *
     * <pre>
     * t=0     读线程读到旧值，随后开始慢处理
     * t=100   写线程更新 DB、删除缓存、登记延迟删除
     * t=500   读线程把旧值回填缓存  ← 此刻缓存是脏的
     * t=1000  延迟删除执行，缓存被清空
     * t=1100  再次读取，回源拿到新值
     * </pre>
     *
     * <p>每一步都是显式等待，不依赖调度运气；因此可以稳定复现，也能稳定断言。</p>
     */
    public RaceResult race(long productId) {
        // 每次都把 DB 和缓存重置成「旧值」，保证实验可重复
        Product oldValue = new Product(productId, "旧名称", new BigDecimal("399.00"), 1, LocalDateTime.now());
        repository.upsert(oldValue);
        String cacheKey = cacheKey(productId);
        redis.opsForValue().set(cacheKey, serialize(oldValue), Duration.ofSeconds(ttlSeconds));

        List<RaceEvent> events = Collections.synchronizedList(new ArrayList<>());
        long start = System.currentTimeMillis();
        events.add(new RaceEvent(0, "准备", "DB 和缓存都是旧值: " + oldValue.name()));

        // 读线程立刻拿到旧快照，然后 sleep 500ms 模拟慢处理；真正的“回填”在 join 之后
        CompletableFuture<Product> slowRead = CompletableFuture.supplyAsync(() -> {
            Product snapshot = repository.findById(productId)
                    .orElseThrow(() -> new IllegalStateException("商品不存在"));
            sleep(500);
            return snapshot;
        });

        // 等读线程已经读到旧快照，再执行写路径，制造「删除之后回填」的时序
        sleep(100);
        repository.update(productId, "新名称", new BigDecimal("499.00"));
        redis.delete(cacheKey);
        long dueAt = delayQueue.schedule(cacheKey, delayedDeleteDelayMs);
        events.add(new RaceEvent(System.currentTimeMillis() - start,
                "写线程：更新 DB + 删除缓存",
                "延迟删除已登记，约 " + (dueAt - System.currentTimeMillis()) + "ms 后执行"));

        // 读线程结束：把 500ms 前读到的旧值写回缓存，覆盖掉刚删除的状态
        Product snapshot = slowRead.join();
        redis.opsForValue().set(cacheKey, serialize(snapshot), Duration.ofSeconds(ttlSeconds));
        String afterRefill = redis.opsForValue().get(cacheKey);
        events.add(new RaceEvent(System.currentTimeMillis() - start,
                "读线程：把读到的旧值回填缓存", afterRefill));

        // 等延迟队列执行第二次删除（多留 500ms 容差），再观察缓存是否真的被清掉
        sleep(Math.max(0, dueAt + 500 - System.currentTimeMillis()));
        String afterDelayedDelete = redis.opsForValue().get(cacheKey);
        events.add(new RaceEvent(System.currentTimeMillis() - start,
                "延迟队列：第二次删除",
                afterDelayedDelete == null ? "缓存已清空" : afterDelayedDelete));

        // 第三次读发生在二次删除之后：未命中 → 回源 → 拿到新值
        ReadResult finalRead = read(productId, 0L);
        events.add(new RaceEvent(System.currentTimeMillis() - start,
                "再次读取",
                finalRead.source() + " → " + finalRead.product().name()));

        return new RaceResult(productId, List.copyOf(events), afterRefill, afterDelayedDelete,
                finalRead.product(), finalRead.source());
    }

    /** 同时查看缓存内容、TTL 和数据库当前值，用来确认双删是否生效。 */
    public InspectResult inspect(long id) {
        String cacheKey = cacheKey(id);
        String cached = redis.opsForValue().get(cacheKey);
        // TTL 单位毫秒；-2 表示 key 不存在，-1 表示没有设置过期时间
        Long ttl = redis.getExpire(cacheKey, java.util.concurrent.TimeUnit.MILLISECONDS);
        return new InspectResult(id, cacheKey, cached, ttl == null ? -2 : ttl,
                repository.findById(id).orElse(null));
    }

    /** 缓存 key 只由商品 id 决定，保证读写路径命中同一个 key。 */
    private String cacheKey(long id) {
        return KEY_PREFIX + id;
    }

    /** Jackson 3（tools.jackson）序列化；失败直接抛错，避免把坏数据写进缓存。 */
    private String serialize(Product product) {
        try {
            return objectMapper.writeValueAsString(product);
        } catch (JacksonException e) {
            throw new IllegalStateException("商品序列化失败", e);
        }
    }

    /** 反序列化失败说明缓存格式已不兼容（例如 Product 字段变更），应清理 key 而不是继续返回坏数据。 */
    private Product deserialize(String json) {
        try {
            return objectMapper.readValue(json, Product.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("商品反序列化失败", e);
        }
    }

    /** 演示用 sleep：非正数直接返回，被中断时恢复标记。 */
    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
