package com.example.demo.concurrency.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.concurrency.config.DemoSettings;
import com.example.demo.concurrency.lock.ReentrantLockDemo;
import com.example.demo.concurrency.support.Params;

/**
 * 场景 2 的 HTTP 入口：ReentrantLock 可重入与 AQS 队列。
 */
@RestController
@RequestMapping("/api/lock")
public class ReentrantLockController {

    private final ReentrantLockDemo demo;
    private final DemoSettings settings;

    /** 注入场景实现与参数上限。 */
    public ReentrantLockController(ReentrantLockDemo demo, DemoSettings settings) {
        this.demo = demo;
        this.settings = settings;
    }

    /**
     * 持有者重入三层，N 个等待者排队；结束后故意多 unlock 一次。
     *
     * @param waiters    等待者线程数，范围 1~demo.concurrency.max-threads
     * @param holdMillis 队列排满后继续持锁的时间（毫秒），用于稳定采样队列长度
     * @return holdCount 变化、队列长度采样、获取顺序、IllegalMonitorStateException 记录
     */
    @GetMapping("/reentrant")
    public ReentrantLockDemo.Result reentrant(
            @RequestParam(defaultValue = "9") int waiters,
            @RequestParam(defaultValue = "300") long holdMillis) {
        int count = Params.between(waiters, 1, settings.maxThreads(), "waiters");
        long hold = Params.between(holdMillis, 50, settings.maxHoldMs(), "holdMillis");
        return demo.run(count, hold);
    }
}
