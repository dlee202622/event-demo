package com.example.demo.concurrency.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.concurrency.config.DemoSettings;
import com.example.demo.concurrency.lock.FairnessDemo;
import com.example.demo.concurrency.support.Params;

/**
 * 场景 3 的 HTTP 入口：公平锁与非公平锁的插队差异。
 */
@RestController
@RequestMapping("/api/lock")
public class FairnessController {

    private final FairnessDemo demo;
    private final DemoSettings settings;

    /** 注入场景实现与参数上限。 */
    public FairnessController(FairnessDemo demo, DemoSettings settings) {
        this.demo = demo;
        this.settings = settings;
    }

    /**
     * 固定排队顺序后让持有者释放并立刻重抢，观察它排到哪里。
     *
     * @param fair    true 公平锁；false 默认非公平锁
     * @param threads 等待者数量，范围 2~demo.concurrency.max-threads
     * @param rounds  轮数（1~5）；非公平锁的插队是概率事件，多跑几轮更容易看到
     * @return 每轮排队顺序、实际获取顺序、holder 位置与插队轮数
     */
    @GetMapping("/fairness")
    public FairnessDemo.Result fairness(
            @RequestParam(defaultValue = "true") boolean fair,
            @RequestParam(defaultValue = "8") int threads,
            @RequestParam(defaultValue = "5") int rounds) {
        int count = Params.between(threads, 2, settings.maxThreads(), "threads");
        int roundCount = Params.between(rounds, 1, 5, "rounds");
        return demo.run(fair, count, roundCount);
    }
}
