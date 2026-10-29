package com.example.demo.concurrency.controller;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 场景索引：列出六个核心实验和三个进阶实验的路径、类型和观察点，
 * 方便从浏览器/curl 逐个进入。
 */
@RestController
@RequestMapping("/api/lock")
public class ScenarioController {

    /**
     * 返回模块简介与场景清单。
     *
     * <p>不执行任何并发实验，只做导航；真正跑实验的接口见 {@link Scenario#path()}。</p>
     */
    @GetMapping
    public Map<String, Object> index() {
        return Map.of(
                "module", "demo-concurrency",
                "port", 9007,
                "scenarios", List.of(
                        new Scenario("核心", "/api/lock/synchronized",
                                "synchronized 互斥；对比 locked=false 时 maxConcurrent=2，线程快照出现 BLOCKED + lockOwner"),
                        new Scenario("核心", "/api/lock/reentrant",
                                "同一线程重入 3 层 holdCount=1/2/3；AQS 队列长度、获取顺序、多 unlock 抛异常"),
                        new Scenario("对比", "/api/lock/fairness",
                                "公平 vs 非公平：释放后重抢，公平锁排队尾，非公平锁插队"),
                        new Scenario("边界", "/api/lock/interrupt",
                                "synchronized 阻塞中中断无效 vs lockInterruptibly 立即抛 InterruptedException"),
                        new Scenario("故障", "/api/lock/deadlock",
                                "AB-BA 死锁的 ThreadMXBean 检测与固定顺序修复"),
                        new Scenario("故障/对比", "/api/lock/counter/lost-updates 与 /api/lock/counter/compare",
                                "确定性丢更新；none/synchronized/atomic/longadder 的结果与吞吐对比"),
                        new Scenario("进阶", "/api/lock/watchdog",
                                "租约到期 vs 看门狗续期：overlap、renewCount、释放后是否停止续期"),
                        new Scenario("进阶", "/api/lock/counter/striped",
                                "自研分段计数 vs AtomicLong vs LongAdder：Cell 分布与吞吐"),
                        new Scenario("进阶", "/api/lock/sharded-map",
                                "全局锁 / 16 分片 / 热点 key：maxConcurrent 与耗时")
                ));
    }

    /**
     * 一个场景的导航信息。
     *
     * @param type         场景类型：核心 / 对比 / 边界 / 故障
     * @param path         可直接 curl 的路径
     * @param whatToWatch  最值得看的字段或现象
     */
    public record Scenario(String type, String path, String whatToWatch) {
    }
}
