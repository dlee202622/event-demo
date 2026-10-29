package com.example.demo.concurrency.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.concurrency.config.DemoSettings;
import com.example.demo.concurrency.lock.WatchdogDemo;
import com.example.demo.concurrency.support.Params;

/**
 * 进阶场景 1 的 HTTP 入口：租约锁在没有/有看门狗时的差异。
 */
@RestController
@RequestMapping("/api/lock")
public class WatchdogController {

    private final WatchdogDemo demo;
    private final DemoSettings settings;

    /** 注入实验实现与持锁时间上限。 */
    public WatchdogController(WatchdogDemo demo, DemoSettings settings) {
        this.demo = demo;
        this.settings = settings;
    }

    /**
     * 同一时序下对比租约自然过期与看门狗续期。
     *
     * <p>默认 lease=800ms、hold=2500ms：无看门狗时 B 会在约 1000ms 闯入；
     * 有看门狗时 B 会等到 A 释放（约 2500ms）才进入。一次请求约 2.5~2.8 秒。</p>
     *
     * @param leaseMillis 租约时长，范围 100~2000 毫秒
     * @param holdMillis  A 的业务执行时间，范围 500~demo.concurrency.max-hold-ms
     * @param watchdog    true 开启续期；false 让租约自然过期
     * @return overlap、renewCount、staleReleaseIgnored 与完整时间线
     */
    @GetMapping("/watchdog")
    public WatchdogDemo.WatchdogResult watchdog(
            @RequestParam(defaultValue = "800") long leaseMillis,
            @RequestParam(defaultValue = "2500") long holdMillis,
            @RequestParam(defaultValue = "false") boolean watchdog) {
        long lease = Params.between(leaseMillis, 100, 2_000, "leaseMillis");
        long hold = Params.between(holdMillis, 500, settings.maxHoldMs(), "holdMillis");
        return demo.run(lease, hold, watchdog);
    }
}
