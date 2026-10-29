package com.example.demo.concurrency.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.concurrency.config.DemoSettings;
import com.example.demo.concurrency.lock.SynchronizedLockDemo;
import com.example.demo.concurrency.support.Params;

/**
 * 场景 1 的 HTTP 入口：synchronized 互斥与 monitor 观测。
 */
@RestController
@RequestMapping("/api/lock")
public class SynchronizedController {

    private final SynchronizedLockDemo demo;
    private final DemoSettings settings;

    /** 注入场景实现与参数上限。 */
    public SynchronizedController(SynchronizedLockDemo demo, DemoSettings settings) {
        this.demo = demo;
        this.settings = settings;
    }

    /**
     * 两个线程同时冲同一段临界区。
     *
     * @param locked    true（默认）走 synchronized；false 裸跑，作为“没有互斥”的反例
     * @param holdMillis 临界区停留时间（毫秒），范围 100~demo.concurrency.max-hold-ms
     * @return 时间线、最大并发数、线程快照；加锁时快照里能看到 BLOCKED + lockOwner
     */
    @GetMapping("/synchronized")
    public SynchronizedLockDemo.Result synchronizedDemo(
            @RequestParam(defaultValue = "true") boolean locked,
            @RequestParam(defaultValue = "600") long holdMillis) {
        long hold = Params.between(holdMillis, 100, settings.maxHoldMs(), "holdMillis");
        return demo.run(locked, hold);
    }
}
