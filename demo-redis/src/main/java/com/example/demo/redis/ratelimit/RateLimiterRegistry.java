package com.example.demo.redis.ratelimit;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * 按名字查找限流算法，方便 URL 直接指定，例如 {@code /api/redis/ratelimit/sliding_log/hit}。
 *
 * <p>Spring 会把所有 {@link RateLimiter} Bean 注入到 List 里，新增算法只要加一个
 * {@code @Component}，这里无需改动。</p>
 *
 * <p>用 TreeMap 而不是 HashMap，是为了让 {@code /algorithms} 和 compare 的输出顺序稳定，
 * 方便文档里贴结果、也方便测试断言。</p>
 */
@Service
public class RateLimiterRegistry {

    private final Map<String, RateLimiter> limiters;

    /** Spring 注入全部限流器实现；顺序由 TreeMap 统一，不受 Bean 扫描顺序影响。 */
    public RateLimiterRegistry(List<RateLimiter> limiters) {
        // 名字重复时保留先注入的那个，避免 silently 覆盖导致 URL 行为不确定
        this.limiters = limiters.stream()
                .collect(Collectors.toMap(RateLimiter::name, l -> l, (a, b) -> a, TreeMap::new));
    }

    /**
     * 找不到时直接抛 IllegalArgumentException，由 ApiExceptionHandler 转成 400，
     * 并在错误信息里列出全部可选值，避免调用方猜。
     */
    public RateLimiter get(String name) {
        RateLimiter limiter = limiters.get(name);
        if (limiter == null) {
            throw new IllegalArgumentException("未知算法: " + name + "，可选: " + limiters.keySet());
        }
        return limiter;
    }

    /** 按名字排序的全部算法，用于 compare / boundary 实验和接口展示。 */
    public List<RateLimiter> all() {
        return List.copyOf(limiters.values());
    }
}
