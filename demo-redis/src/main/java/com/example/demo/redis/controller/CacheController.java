package com.example.demo.redis.controller;

import com.example.demo.redis.cache.DelayTaskQueue;
import com.example.demo.redis.cache.ProductCacheService;
import com.example.demo.redis.cache.ProductCacheService.InspectResult;
import com.example.demo.redis.cache.ProductCacheService.RaceResult;
import com.example.demo.redis.cache.ProductCacheService.ReadResult;
import com.example.demo.redis.cache.ProductCacheService.UpdateResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 缓存一致性演示入口：读缓存、更新 + 延迟双删、竞态复现。
 *
 * <p>典型顺序：先 {@code GET /product/1} 看回源并回填，再 {@code PUT /product/1}
 * 观察两次删除，最后 {@code POST /race} 看旧值回填如何被延迟删除修正。</p>
 */
@RestController
@RequestMapping("/api/redis/cache")
public class CacheController {

    private final ProductCacheService cacheService;
    private final DelayTaskQueue delayQueue;

    /** 注入缓存服务和延迟队列；队列用于查看 pending/executed 状态。 */
    public CacheController(ProductCacheService cacheService, DelayTaskQueue delayQueue) {
        this.cacheService = cacheService;
        this.delayQueue = delayQueue;
    }

    /** 更新请求体；price 用 BigDecimal 避免浮点误差。 */
    public record UpdateRequest(String name, BigDecimal price) {
    }

    /**
     * 读商品：命中缓存返回 source=CACHE，未命中回源返回 source=DB 并回填。
     *
     * @param slowReadMs 可选，覆盖默认的模拟慢查询耗时；配合竞态实验使用
     */
    @GetMapping("/product/{id}")
    public ReadResult read(@PathVariable long id,
                           @RequestParam(required = false) Long slowReadMs) {
        return cacheService.read(id, slowReadMs);
    }

    /** 更新商品：DB → 删缓存 → 登记延迟删除；返回三个阶段的相对时间。 */
    @PutMapping("/product/{id}")
    public UpdateResult update(@PathVariable long id, @RequestBody UpdateRequest request) {
        return cacheService.update(id, request.name(), request.price());
    }

    /** 查看缓存内容、TTL 和数据库当前值，用来确认双删是否真的清掉了缓存。 */
    @GetMapping("/product/{id}/inspect")
    public InspectResult inspect(@PathVariable long id) {
        return cacheService.inspect(id);
    }

    /**
     * 用两个线程确定性复现「旧值回填 → 延迟删除修正」。
     *
     * <p>会先把该商品重置为旧值，因此不要对正在使用的业务数据调用；
     * 默认 id=99 是演示专用商品。</p>
     */
    @PostMapping("/race")
    public RaceResult race(@RequestParam(defaultValue = "99") long id) {
        return cacheService.race(id);
    }

    /** 尚未执行的延迟删除任务，按到期时间排序。 */
    @GetMapping("/delay/pending")
    public List<Map<String, Object>> pending() {
        return delayQueue.pending();
    }

    /** 最近执行过的延迟删除（进程内最多 20 条），用于确认第二次删除确实发生。 */
    @GetMapping("/delay/executed")
    public List<DelayTaskQueue.ExecutedTask> executed() {
        return delayQueue.executed();
    }
}
