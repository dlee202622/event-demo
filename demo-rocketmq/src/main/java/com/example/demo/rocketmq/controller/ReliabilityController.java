package com.example.demo.rocketmq.controller;

import com.example.demo.rocketmq.config.ReliabilityTopics;
import com.example.demo.rocketmq.producer.OrderlyProducer;
import com.example.demo.rocketmq.support.RocketMQConsumerControl;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 消息可靠性场景的演示接口：幂等、顺序、积压、持久化。
 *
 * <p>这些接口只负责“制造现象”，真正的处理逻辑在对应的 Consumer 里。
 * 积压/持久化通过 {@link RocketMQConsumerControl} 暂停消费者，
 * 堆积量请在 Dashboard（http://localhost:19876）的消费者页查看 Diff。</p>
 */
@RestController
@RequestMapping("/api/rocketmq")
public class ReliabilityController {

    /** 单次批量发送上限，避免误操作把本地 Broker 打满。 */
    private static final int MAX_BATCH = 1000;

    private final RocketMQTemplate rocketTemplate;
    private final OrderlyProducer orderlyProducer;
    private final RocketMQConsumerControl consumerControl;

    public ReliabilityController(RocketMQTemplate rocketTemplate,
                                 OrderlyProducer orderlyProducer,
                                 RocketMQConsumerControl consumerControl) {
        this.rocketTemplate = rocketTemplate;
        this.orderlyProducer = orderlyProducer;
        this.consumerControl = consumerControl;
    }

    // ------------------------------------------------------------------
    // 1. 幂等
    // ------------------------------------------------------------------

    /**
     * 发送幂等演示消息。
     *
     * <p>{@code duplicate=true} 时连发两条相同的业务 ID，模拟重投；
     * 消费端只会处理一次，第二条打印 duplicate ignored。</p>
     */
    @PostMapping("/idempotent")
    public Map<String, String> sendIdempotent(
            @RequestParam(defaultValue = "ID-1") String messageId,
            @RequestParam(defaultValue = "true") boolean duplicate) {
        int times = duplicate ? 2 : 1;
        for (int i = 0; i < times; i++) {
            // 消息体就是业务唯一 ID，跨重投保持不变；不要依赖 Broker 生成的 messageId
            rocketTemplate.syncSend(ReliabilityTopics.IDEMPOTENT_TOPIC, messageId);
        }
        return Map.of(
                "topic", ReliabilityTopics.IDEMPOTENT_TOPIC,
                "messageId", messageId,
                "sent", String.valueOf(times));
    }

    // ------------------------------------------------------------------
    // 2. 顺序
    // ------------------------------------------------------------------

    /**
     * 批量顺序发送：同一个 sharding key 的消息进入同一个队列，
     * 配合 ORDERLY 消费者按 step-1、step-2 ... 的顺序打印。
     */
    @PostMapping("/ordered-batch")
    public Map<String, String> sendOrderedBatch(
            @RequestParam(defaultValue = "ORDER-1") String orderKey,
            @RequestParam(defaultValue = "10") int count) {
        int total = clamp(count);
        for (int i = 1; i <= total; i++) {
            orderlyProducer.send(orderKey, "step-" + i);
        }
        return Map.of(
                "topic", "demo-orderly-topic",
                "orderKey", orderKey,
                "sent", String.valueOf(total));
    }

    /** 反例：普通发送 + 并发消费，日志顺序会被打乱。 */
    @PostMapping("/unordered")
    public Map<String, String> sendUnordered(@RequestParam(defaultValue = "10") int count) {
        int total = clamp(count);
        for (int i = 1; i <= total; i++) {
            rocketTemplate.syncSend(ReliabilityTopics.UNORDERED_TOPIC, "step-" + i);
        }
        return Map.of(
                "topic", ReliabilityTopics.UNORDERED_TOPIC,
                "sent", String.valueOf(total),
                "consumeMode", "CONCURRENTLY");
    }

    // ------------------------------------------------------------------
    // 3. 积压
    // ------------------------------------------------------------------

    /**
     * 批量发送积压演示消息。
     *
     * <p>建议顺序：先 /backlog/pause，再 /backlog/send?count=200，
     * 到 Dashboard 看 Diff 增长，最后 /backlog/resume 观察消费。</p>
     */
    @PostMapping("/backlog/send")
    public Map<String, String> sendBacklog(@RequestParam(defaultValue = "100") int count) {
        int total = clamp(count);
        for (int i = 1; i <= total; i++) {
            rocketTemplate.syncSend(ReliabilityTopics.BACKLOG_TOPIC, "backlog-" + i);
        }
        return Map.of(
                "topic", ReliabilityTopics.BACKLOG_TOPIC,
                "sent", String.valueOf(total),
                "next", "到 Dashboard 查看 Diff，然后 /api/rocketmq/backlog/resume");
    }

    /** 暂停积压消费者：客户端停止拉取，消费位点不再推进。 */
    @PostMapping("/backlog/pause")
    public Map<String, String> pauseBacklog() {
        consumerControl.suspend(ReliabilityTopics.BACKLOG_GROUP);
        return status(ReliabilityTopics.BACKLOG_TOPIC, ReliabilityTopics.BACKLOG_GROUP);
    }

    /** 恢复积压消费者：从暂停时的位点继续消费。 */
    @PostMapping("/backlog/resume")
    public Map<String, String> resumeBacklog() {
        consumerControl.resume(ReliabilityTopics.BACKLOG_GROUP);
        return status(ReliabilityTopics.BACKLOG_TOPIC, ReliabilityTopics.BACKLOG_GROUP);
    }

    /** 查看消费者是否处于暂停状态（堆积量看 Dashboard）。 */
    @GetMapping("/backlog/status")
    public Map<String, String> backlogStatus() {
        return status(ReliabilityTopics.BACKLOG_TOPIC, ReliabilityTopics.BACKLOG_GROUP);
    }

    // ------------------------------------------------------------------
    // 4. 持久化
    // ------------------------------------------------------------------

    /**
     * 发送持久化演示消息。
     *
     * <p>RocketMQ 默认把消息写入 CommitLog 并刷盘，所以这里用同步发送确认消息已落到 Broker。
     * 验证步骤：pause → send → {@code docker compose restart rocketmq-broker} →
     * resume，消息仍会被投递。注意是 restart 而不是 down/up，
     * 后者会重建容器并清空未挂载数据卷的 Broker 数据。</p>
     */
    @PostMapping("/persistence/send")
    public Map<String, String> sendPersistence(@RequestParam(defaultValue = "3") int count) {
        int total = clamp(count);
        for (int i = 1; i <= total; i++) {
            // syncSend 返回 SendResult 才代表 Broker 已接收并写入存储
            rocketTemplate.syncSend(ReliabilityTopics.PERSISTENCE_TOPIC, "persist-" + i);
        }
        return Map.of(
                "topic", ReliabilityTopics.PERSISTENCE_TOPIC,
                "sent", String.valueOf(total),
                "next", "docker compose restart rocketmq-broker 后调用 /persistence/resume");
    }

    /** 暂停持久化消费者，避免消息发送后被立刻消费掉。 */
    @PostMapping("/persistence/pause")
    public Map<String, String> pausePersistence() {
        consumerControl.suspend(ReliabilityTopics.PERSISTENCE_GROUP);
        return status(ReliabilityTopics.PERSISTENCE_TOPIC, ReliabilityTopics.PERSISTENCE_GROUP);
    }

    /** 恢复持久化消费者：重启 Broker 后调用它，验证消息仍能投递。 */
    @PostMapping("/persistence/resume")
    public Map<String, String> resumePersistence() {
        consumerControl.resume(ReliabilityTopics.PERSISTENCE_GROUP);
        return status(ReliabilityTopics.PERSISTENCE_TOPIC, ReliabilityTopics.PERSISTENCE_GROUP);
    }

    /** 查看持久化消费者状态。 */
    @GetMapping("/persistence/status")
    public Map<String, String> persistenceStatus() {
        return status(ReliabilityTopics.PERSISTENCE_TOPIC, ReliabilityTopics.PERSISTENCE_GROUP);
    }

    // ------------------------------------------------------------------
    // 公共辅助
    // ------------------------------------------------------------------

    /** 批量参数做边界收敛，避免一次请求发送过多消息。 */
    private int clamp(int count) {
        return Math.max(1, Math.min(count, MAX_BATCH));
    }

    private Map<String, String> status(String topic, String consumerGroup) {
        return Map.of(
                "topic", topic,
                "consumerGroup", consumerGroup,
                "suspended", String.valueOf(consumerControl.isSuspended(consumerGroup)),
                "hint", "堆积量请在 Dashboard http://localhost:19876 的消费者页查看 Diff");
    }
}
