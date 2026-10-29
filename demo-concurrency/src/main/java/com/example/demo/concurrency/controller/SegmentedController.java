package com.example.demo.concurrency.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.concurrency.config.DemoSettings;
import com.example.demo.concurrency.lock.ShardedMapDemo;
import com.example.demo.concurrency.lock.StripedCounterDemo;
import com.example.demo.concurrency.support.Params;

/**
 * 进阶场景 2、3 的 HTTP 入口：分段计数与分片 Map 锁。
 */
@RestController
@RequestMapping("/api/lock")
public class SegmentedController {

    /** 一次请求里三种模式串行执行，限制最坏情况的总忙等时间，避免长时间占满 CPU。 */
    private static final long MAX_TOTAL_WORK_MICROS = 2_000_000;

    private final StripedCounterDemo stripedDemo;
    private final ShardedMapDemo shardedDemo;
    private final DemoSettings settings;

    /** 注入两个实验实现与线程/迭代上限。 */
    public SegmentedController(StripedCounterDemo stripedDemo, ShardedMapDemo shardedDemo,
                               DemoSettings settings) {
        this.stripedDemo = stripedDemo;
        this.shardedDemo = shardedDemo;
        this.settings = settings;
    }

    /**
     * 手写分段计数与 atomic / LongAdder 对比。
     *
     * <p>cells=1 会退化成单点 CAS，可与 cells=16、32 对照；Cell 数量会自动向上取到 2 的幂。</p>
     *
     * @param threads    线程数，范围 2~demo.concurrency.max-threads
     * @param iterations 每线程迭代次数，范围 1000~demo.concurrency.max-iterations
     * @param cells      期望 Cell 数，范围 1~64
     * @param rounds     正式测量轮数，范围 1~5；每轮取中位数
     * @return 三种写法的耗时、有效 ops/ms 与分段分布
     */
    @GetMapping("/counter/striped")
    public StripedCounterDemo.Result striped(
            @RequestParam(defaultValue = "32") int threads,
            @RequestParam(defaultValue = "100000") int iterations,
            @RequestParam(defaultValue = "16") int cells,
            @RequestParam(defaultValue = "3") int rounds) {
        int threadCount = Params.between(threads, 2, settings.maxThreads(), "threads");
        int iterationCount = Params.between(iterations, 1_000, settings.maxIterations(), "iterations");
        int cellCount = Params.between(cells, 1, 64, "cells");
        int roundCount = Params.between(rounds, 1, 5, "rounds");
        return stripedDemo.compare(threadCount, iterationCount, cellCount, roundCount);
    }

    /**
     * 全局锁 / 分片锁 / 热点 key 三种策略对比。
     *
     * <p>一次请求依次跑三种模式，默认约 1 秒；总忙等时间
     * threads × operations × workMicros 超过 2,000,000µs（约 2 秒）会被拒绝。</p>
     *
     * @param threads    线程数，范围 2~demo.concurrency.max-threads
     * @param keys       普通模式的 key 总数，范围 1~256
     * @param operations 每线程操作次数，范围 1~1000
     * @param shards     分片数，范围 1~64
     * @param workMicros 锁内忙等微秒数，范围 10~1000
     * @return 三种模式的 maxConcurrent、耗时、校验和与分片分布
     */
    @GetMapping("/sharded-map")
    public ShardedMapDemo.Result shardedMap(
            @RequestParam(defaultValue = "8") int threads,
            @RequestParam(defaultValue = "64") int keys,
            @RequestParam(defaultValue = "300") int operations,
            @RequestParam(defaultValue = "16") int shards,
            @RequestParam(defaultValue = "200") int workMicros) {
        int threadCount = Params.between(threads, 2, settings.maxThreads(), "threads");
        int keyCount = Params.between(keys, 1, 256, "keys");
        int operationCount = Params.between(operations, 1, 1_000, "operations");
        int shardCount = Params.between(shards, 1, 64, "shards");
        int work = Params.between(workMicros, 10, 1_000, "workMicros");
        long totalWorkMicros = (long) threadCount * operationCount * work;
        if (totalWorkMicros > MAX_TOTAL_WORK_MICROS) {
            // 不静默 clamp：直接告诉调用方哪一组参数会让请求跑太久
            throw new IllegalArgumentException("threads × operations × workMicros 不能超过 "
                    + MAX_TOTAL_WORK_MICROS + "（约 2 秒），当前=" + totalWorkMicros);
        }
        return shardedDemo.run(threadCount, keyCount, operationCount, shardCount, work);
    }
}
