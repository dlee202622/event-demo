package com.example.demo.redis.controller;

import com.example.demo.redis.ratelimit.RateLimiter;
import com.example.demo.redis.ratelimit.RateLimiterRegistry;
import com.example.demo.redis.support.RateLimitDecision;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 限流演示：单次判定、突发压测、结构查看，以及跨算法对比。
 *
 * <p>接口分三组：</p>
 * <ul>
 *   <li>{@code /{algorithm}/hit|burst|state}：观察单个算法；</li>
 *   <li>{@code /compare}：同一参数下六种算法并行对比；</li>
 *   <li>{@code /boundary}：跨窗口边界的固定窗口 vs 滑动窗口实验。</li>
 * </ul>
 *
 * <p>compare/boundary 会真实等待一段时间（默认约 1.5–5 秒），参数已做范围保护；
 * 这些接口用于教学观察，不应放在生产请求路径上。</p>
 */
@RestController
@RequestMapping("/api/redis/ratelimit")
public class RateLimitController {

    private final RateLimiterRegistry registry;
    private final StringRedisTemplate redis;

    /** 注入算法注册表和模板；模板只用于 reset 时删除 key。 */
    public RateLimitController(RateLimiterRegistry registry, StringRedisTemplate redis) {
        this.registry = registry;
        this.redis = redis;
    }

    /**
     * @param requested       总共发了多少次
     * @param allowed/denied  放行/拒绝数量，二者之和等于 requested
     * @param decisions       每次判定的完整结果，可观察“第几次开始被拒”
     */
    public record BurstResult(String algorithm, long limit, long windowSeconds, int requested,
                              int allowed, int denied, long elapsedMillis,
                              List<RateLimitDecision> decisions) {
    }

    /**
     * @param firstDeniedAtMillis 第一次被拒绝的相对时间；-1 表示全程没有被拒绝
     * @param description         算法的一句话说明，便于对比表直接展示
     */
    public record CompareRow(String algorithm, String description, long limit, long windowSeconds,
                             int requested, int allowed, int denied, long firstDeniedAtMillis) {
    }

    /**
     * @param firstBatchAllowed  窗口结束前那一批放行了多少
     * @param secondBatchAllowed 跨过边界后那一批放行了多少
     * @param acceptedWithinMillis 第二批中“首个放行 → 最后一个放行”的跨度，用于区分算法
     */
    public record BoundaryRow(String algorithm, int firstBatchAllowed, int secondBatchAllowed,
                              long acceptedWithinMillis, String verdict) {
    }

    /** 列出全部算法名与一句话说明，供页面渲染下拉框；顺序由注册表的 TreeMap 保证。 */
    @GetMapping("/algorithms")
    public List<Map<String, Object>> algorithms() {
        return registry.all().stream()
                .map(limiter -> Map.<String, Object>of(
                        "name", limiter.name(),
                        "description", limiter.description()))
                .toList();
    }

    /** 单次请求：返回是否放行、剩余额度、最早重试时间。 */
    @GetMapping("/{algorithm}/hit")
    public RateLimitDecision hit(@PathVariable String algorithm,
                                 @RequestParam(defaultValue = "user-1") String key,
                                 @RequestParam(defaultValue = "5") long limit,
                                 @RequestParam(defaultValue = "10") long windowSeconds) {
        validate(limit, windowSeconds);
        return registry.get(algorithm).tryAcquire(key, limit, windowSeconds);
    }

    /**
     * 连续快速请求 N 次，观察放行/拒绝分布。
     *
     * <p>串行发送而不是并发：并发时“第几次被拒”取决于线程调度，结果不可复现；
     * 串行能让固定窗口稳定表现为“前 limit 次全部放行”。</p>
     */
    @GetMapping("/{algorithm}/burst")
    public BurstResult burst(@PathVariable String algorithm,
                             @RequestParam(required = false) String key,
                             @RequestParam(defaultValue = "5") long limit,
                             @RequestParam(defaultValue = "10") long windowSeconds,
                             @RequestParam(defaultValue = "12") int requests) {
        validate(limit, windowSeconds);
        // 单次最多 100 次，避免接口被误用成长时间阻塞
        int count = clamp(requests, 1, 100);
        RateLimiter limiter = registry.get(algorithm);
        // 不传 key 时生成随机 key：多次压测互不影响，也不会污染用户传入的维度
        String actualKey = key == null ? "burst-" + UUID.randomUUID() : key;
        long start = System.currentTimeMillis();
        List<RateLimitDecision> decisions = new ArrayList<>();
        int allowed = 0;
        for (int i = 0; i < count; i++) {
            RateLimitDecision decision = limiter.tryAcquire(actualKey, limit, windowSeconds);
            decisions.add(decision);
            if (decision.allowed()) {
                allowed++;
            }
        }
        return new BurstResult(limiter.name(), limit, windowSeconds, count, allowed,
                count - allowed, System.currentTimeMillis() - start, decisions);
    }

    /** 查看算法在 Redis 里的真实结构：String / Hash / ZSet；用于理解实现而不是只看返回码。 */
    @GetMapping("/{algorithm}/state")
    public Map<String, Object> state(@PathVariable String algorithm,
                                     @RequestParam(defaultValue = "user-1") String key) {
        return registry.get(algorithm).state(key);
    }

    /** 删除该算法对应的 Redis key，方便重复实验；返回 key 名和是否真的删除。 */
    @DeleteMapping("/{algorithm}/state")
    public Map<String, Object> reset(@PathVariable String algorithm,
                                     @RequestParam(defaultValue = "user-1") String key) {
        Map<String, Object> state = registry.get(algorithm).state(key);
        String redisKey = String.valueOf(state.get("redisKey"));
        return Map.of("redisKey", redisKey, "deleted", Boolean.TRUE.equals(redis.delete(redisKey)));
    }

    /**
     * 同一组参数下，6 种算法处理同一段流量（间隔 intervalMillis）的放行差异。
     * 各算法并行执行，避免总耗时叠加。
     *
     * @param requests       每种算法发送的请求数
     * @param intervalMillis 两次请求之间的间隔；太短会让所有算法都表现为“瞬间打满”
     */
    @GetMapping("/compare")
    public List<CompareRow> compare(@RequestParam(defaultValue = "5") long limit,
                                    @RequestParam(defaultValue = "10") long windowSeconds,
                                    @RequestParam(defaultValue = "15") int requests,
                                    @RequestParam(defaultValue = "100") long intervalMillis) {
        validate(limit, windowSeconds);
        int count = clamp(requests, 1, 100);
        // 至少 10ms：间隔为 0 时固定窗口/滑动窗口的差异会被“同一瞬间”掩盖
        long interval = clamp(intervalMillis, 10, 1000);
        List<RateLimiter> limiters = registry.all();
        // 每个算法一个线程：总耗时约等于最慢的算法，而不是六者相加
        ExecutorService pool = Executors.newFixedThreadPool(limiters.size());
        try {
            List<Future<CompareRow>> futures = new ArrayList<>();
            for (RateLimiter limiter : limiters) {
                futures.add(pool.submit(() -> runCompare(limiter, count, limit, windowSeconds, interval)));
            }
            List<CompareRow> rows = new ArrayList<>();
            // 按提交顺序收集，输出顺序与注册表一致，方便测试和文档引用
            for (Future<CompareRow> future : futures) {
                rows.add(future.get());
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("对比执行失败: " + e.getMessage(), e);
        } finally {
            // 无论成功失败都关闭线程池，避免请求泄漏线程
            pool.shutdownNow();
        }
    }

    /**
     * 单个算法的对比运行：独立随机 key，避免六个算法共用同一份计数。
     *
     * @param firstDeniedAt 记录第一次被拒的相对时间，用于比较“多久开始限流”
     */
    private CompareRow runCompare(RateLimiter limiter, int requests, long limit,
                                  long windowSeconds, long intervalMillis) {
        String key = "compare-" + UUID.randomUUID();
        int allowed = 0;
        long firstDeniedAt = -1;
        long start = System.currentTimeMillis();
        for (int i = 0; i < requests; i++) {
            RateLimitDecision decision = limiter.tryAcquire(key, limit, windowSeconds);
            if (decision.allowed()) {
                allowed++;
            } else if (firstDeniedAt < 0) {
                // 只记第一次：后续拒绝时间对“何时开始限流”没有额外信息
                firstDeniedAt = System.currentTimeMillis() - start;
            }
            // 控制流量节奏；没有间隔时所有算法都会在毫秒内打满
            sleep(intervalMillis);
        }
        return new CompareRow(limiter.name(), limiter.description(), limit, windowSeconds,
                requests, allowed, requests - allowed, firstDeniedAt);
    }

    /**
     * 窗口边界实验：在窗口结束前发一批，跨过边界后再发一批。
     *
     * <p>固定窗口会全部放行（两批落在两个计数器里），滑动窗口会拦住第二批。
     * 为了让两批分别落在边界两侧，实验会先睡到窗口 85% 处，再睡到边界后 200ms。</p>
     *
     * @param windowSeconds 限制在 2–10 秒：太短来不及发完一批，太长会让接口等待过久
     */
    @GetMapping("/boundary")
    public List<BoundaryRow> boundary(@RequestParam(defaultValue = "5") long limit,
                                      @RequestParam(defaultValue = "4") long windowSeconds) {
        validate(limit, windowSeconds);
        if (windowSeconds < 2 || windowSeconds > 10) {
            throw new IllegalArgumentException("边界实验的 windowSeconds 需在 2~10 之间");
        }
        // 只比较“按窗口对齐”的四种算法；令牌桶/漏桶没有固定边界，放了会误导结论
        List<RateLimiter> limiters = List.of(
                registry.get("fixed_window"),
                registry.get("sliding_log"),
                registry.get("sliding_counter"),
                registry.get("sliding_bucket"));
        ExecutorService pool = Executors.newFixedThreadPool(limiters.size());
        try {
            List<Future<BoundaryRow>> futures = new ArrayList<>();
            for (RateLimiter limiter : limiters) {
                futures.add(pool.submit(() -> runBoundary(limiter, limit, windowSeconds)));
            }
            List<BoundaryRow> rows = new ArrayList<>();
            for (Future<BoundaryRow> future : futures) {
                rows.add(future.get());
            }
            return rows;
        } catch (Exception e) {
            throw new IllegalStateException("边界实验失败: " + e.getMessage(), e);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 单个算法的边界实验。
     *
     * <p>时间点由计算得出而不是固定 sleep：先对齐到窗口起点，等到 85% 处发第一批，
     * 再等到边界后 200ms 发第二批。这样无论何时调用，两批都稳定落在边界两侧。</p>
     */
    private BoundaryRow runBoundary(RateLimiter limiter, long limit, long windowSeconds) {
        long windowMs = windowSeconds * 1000;
        long now = System.currentTimeMillis();
        // 当前窗口起点：把 now 向下取整到 windowMs 的整数倍
        long windowStart = now - (now % windowMs);
        // 85% 处：离边界足够近，确保第一批全部落在旧窗口内
        long nearEnd = windowStart + (long) (windowMs * 0.85);
        sleep(nearEnd - now);

        String key = "boundary-" + UUID.randomUUID();
        int firstAllowed = 0;
        for (int i = 0; i < limit; i++) {
            if (limiter.tryAcquire(key, limit, windowSeconds).allowed()) {
                firstAllowed++;
            }
        }

        // 边界后 200ms：给调度留出余量，确保第二批一定落在新窗口
        long boundary = windowStart + windowMs;
        sleep(boundary + 200 - System.currentTimeMillis());
        int secondAllowed = 0;
        long firstAcceptedAt = 0;
        long lastAcceptedAt = 0;
        for (int i = 0; i < limit; i++) {
            if (limiter.tryAcquire(key, limit, windowSeconds).allowed()) {
                secondAllowed++;
                if (firstAcceptedAt == 0) {
                    firstAcceptedAt = System.currentTimeMillis();
                }
                lastAcceptedAt = System.currentTimeMillis();
            }
        }
        // 第二批的放行跨度：滑动窗口通常只有一个瞬间能过，固定窗口会很快放完
        long span = secondAllowed > 0 ? lastAcceptedAt - firstAcceptedAt : 0;
        String verdict = secondAllowed == limit
                ? "第二批全部放行：跨窗口边界实际放大了流量"
                : "第二批被拦截：滑动窗口仍然记得上一批请求";
        return new BoundaryRow(limiter.name(), firstAllowed, secondAllowed, span, verdict);
    }

    /** 参数范围保护：limit 过大或窗口过长会让演示接口长时间占用线程。 */
    private static void validate(long limit, long windowSeconds) {
        if (limit < 1 || limit > 100_000) {
            throw new IllegalArgumentException("limit 需在 1~100000 之间");
        }
        if (windowSeconds < 1 || windowSeconds > 3600) {
            throw new IllegalArgumentException("windowSeconds 需在 1~3600 之间");
        }
    }

    /** 把用户输入夹到安全区间，而不是直接拒绝，保证演示总能跑完。 */
    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** long 版本的同名夹取；intervalMillis 用它限制在 10–1000ms。 */
    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 演示用 sleep：非正数不等待，被中断时恢复标记。 */
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
