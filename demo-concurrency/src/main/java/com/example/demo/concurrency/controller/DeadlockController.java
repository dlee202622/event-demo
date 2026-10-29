package com.example.demo.concurrency.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.concurrency.config.DemoSettings;
import com.example.demo.concurrency.lock.DeadlockDemo;
import com.example.demo.concurrency.support.Params;

/**
 * 场景 5 的 HTTP 入口：AB-BA 死锁复现、检测与修复。
 */
@RestController
@RequestMapping("/api/lock")
public class DeadlockController {

    private final DeadlockDemo demo;
    private final DemoSettings settings;

    /** 注入场景实现与参数上限。 */
    public DeadlockController(DeadlockDemo demo, DemoSettings settings) {
        this.demo = demo;
        this.settings = settings;
    }

    /**
     * 交叉加锁制造死锁，或用固定顺序修复。
     *
     * <p>fixed=false 时最坏会等到 demo.concurrency.deadlock-detect-timeout-ms 才返回；
     * 检测到死锁后会 interrupt 线程并验证 JVM 不再报告死锁。</p>
     *
     * @param fixed     false（默认）交叉顺序；true 固定 A→B 顺序
     * @param holdMillis 拿到第二把锁后持锁的时间（毫秒）
     * @return 是否检出死锁、死锁线程的锁与持有者、清理结果与时间线
     */
    @GetMapping("/deadlock")
    public DeadlockDemo.Result deadlock(
            @RequestParam(defaultValue = "false") boolean fixed,
            @RequestParam(defaultValue = "100") long holdMillis) {
        long hold = Params.between(holdMillis, 20, 1000, "holdMillis");
        return demo.run(fixed, hold, settings.deadlockDetectTimeoutMs());
    }
}
