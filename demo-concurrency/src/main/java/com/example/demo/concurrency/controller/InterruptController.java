package com.example.demo.concurrency.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.concurrency.config.DemoSettings;
import com.example.demo.concurrency.lock.InterruptDemo;
import com.example.demo.concurrency.support.Params;

/**
 * 场景 4 的 HTTP 入口：锁等待期间的中断行为。
 */
@RestController
@RequestMapping("/api/lock")
public class InterruptController {

    private final InterruptDemo demo;
    private final DemoSettings settings;

    /** 注入场景实现与参数上限。 */
    public InterruptController(InterruptDemo demo, DemoSettings settings) {
        this.demo = demo;
        this.settings = settings;
    }

    /**
     * 持有者持锁一段时间，受害者阻塞后被中断。
     *
     * <p>总耗时约为 holdMillis + 中断等待：synchronized 模式要等锁释放，
     * reentrant 模式在中断后立即返回。</p>
     *
     * @param mode               synchronized 或 reentrant（忽略大小写）
     * @param holdMillis         持有者持锁时间，范围 300~demo.concurrency.max-hold-ms
     * @param interruptAfterMillis 确认阻塞后再等多久中断，范围 50~holdMillis-50
     * @return 是否进入临界区、中断响应耗时、中断前快照与时间线
     */
    @GetMapping("/interrupt")
    public InterruptDemo.Result interrupt(
            @RequestParam(defaultValue = "synchronized") String mode,
            @RequestParam(defaultValue = "1200") long holdMillis,
            @RequestParam(defaultValue = "200") long interruptAfterMillis) {
        InterruptDemo.Mode parsedMode = InterruptDemo.Mode.from(mode);
        long hold = Params.between(holdMillis, 300, settings.maxHoldMs(), "holdMillis");
        long after = Params.between(interruptAfterMillis, 50, hold - 50, "interruptAfterMillis");
        return demo.run(parsedMode, hold, after);
    }
}
