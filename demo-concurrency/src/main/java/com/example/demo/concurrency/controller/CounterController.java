package com.example.demo.concurrency.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.concurrency.config.DemoSettings;
import com.example.demo.concurrency.lock.CounterDemo;
import com.example.demo.concurrency.support.Params;

/**
 * 场景 6 的 HTTP 入口：丢更新的确定性复现与四种计数写法对比。
 */
@RestController
@RequestMapping("/api/lock/counter")
public class CounterController {

    private final CounterDemo demo;
    private final DemoSettings settings;

    /** 注入场景实现与参数上限。 */
    public CounterController(CounterDemo demo, DemoSettings settings) {
        this.demo = demo;
        this.settings = settings;
    }

    /**
     * 用栅栏放大竞态窗口，让 expected 与 actual 的差值可精确断言。
     *
     * @param threads    线程数，范围 2~demo.concurrency.max-threads
     * @param iterations 每线程迭代次数，范围 100~demo.concurrency.max-iterations
     * @return expected、actual、lost；orchestrated=true
     */
    @GetMapping("/lost-updates")
    public CounterDemo.LostUpdateResult lostUpdates(
            @RequestParam(defaultValue = "8") int threads,
            @RequestParam(defaultValue = "2000") int iterations) {
        int threadCount = Params.between(threads, 2, settings.maxThreads(), "threads");
        int iterationCount = Params.between(iterations, 100, settings.maxIterations(), "iterations");
        return demo.lostUpdates(threadCount, iterationCount);
    }

    /**
     * 四种写法跑自然竞争流量，比较正确性与吞吐。
     *
     * <p>默认 8 线程 × 100000 次 × 3 轮 × 4 种写法，通常 1~3 秒内返回；
     * 机器较慢时可减小 iterations 或 rounds。</p>
     *
     * @param threads    线程数，范围 2~demo.concurrency.max-threads
     * @param iterations 每线程迭代次数，范围 1000~demo.concurrency.max-iterations
     * @param rounds     每种写法轮数，范围 1~5
     * @return 四种写法的 actuals、是否精确、中位耗时与有效 ops/ms
     */
    @GetMapping("/compare")
    public CounterDemo.CompareResult compare(
            @RequestParam(defaultValue = "8") int threads,
            @RequestParam(defaultValue = "100000") int iterations,
            @RequestParam(defaultValue = "3") int rounds) {
        int threadCount = Params.between(threads, 2, settings.maxThreads(), "threads");
        int iterationCount = Params.between(iterations, 1000, settings.maxIterations(), "iterations");
        int roundCount = Params.between(rounds, 1, 5, "rounds");
        return demo.compare(threadCount, iterationCount, roundCount);
    }
}
