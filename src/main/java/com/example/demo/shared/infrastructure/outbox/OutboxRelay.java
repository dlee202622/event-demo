package com.example.demo.shared.infrastructure.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 发件箱中继（Outbox Relay）：定时把 PENDING 事件投递到消息中间件。
 *
 * <p>本 demo 用日志模拟 Kafka/RocketMQ 的发送动作，真实实现：</p>
 * <pre>
 *   producer.send(new ProducerRecord&lt;&gt;("order-events", event.aggregateId(), event.payload()),
 *                 (metadata, ex) -&gt; { if (ex == null) markSent(event); else retryLater(event); });
 * </pre>
 *
 * <p>关键设计点（也是面试高频考点）：</p>
 * <ul>
 *   <li><b>至少一次</b>：投递成功但标记失败会重投 → 消费端必须按 eventId 幂等；</li>
 *   <li><b>顺序性</b>：以 aggregateId 作为消息 key，同一订单的事件落到同一分区，顺序有保证；</li>
 *   <li><b>多实例并发</b>：用 SKIP LOCKED 抢占待投递记录，避免重复投递；</li>
 *   <li><b>兜底</b>：设置最大重试次数 + 死信表，避免单条毒消息阻塞整个中继。</li>
 * </ul>
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** 事件主题：按聚合类型划分，生产上通常一个上下文一个 topic 或一个 tag。 */
    public static final String TOPIC = "order-events";

    private final OutboxStore outboxStore;

    /** 定时投递开关；测试中关闭以保证断言确定性（仍可手动 flush）。 */
    private final boolean relayEnabled;

    public OutboxRelay(OutboxStore outboxStore,
                       @Value("${demo.outbox.relay-enabled:true}") boolean relayEnabled) {
        this.outboxStore = outboxStore;
        this.relayEnabled = relayEnabled;
    }

    @Scheduled(fixedDelayString = "${demo.outbox.relay-interval-ms:3000}", initialDelay = 2000)
    public void relay() {
        if (!relayEnabled) {
            return;
        }
        flush();
    }

    /** 手动触发一轮投递，返回本轮投递条数（便于测试与演示）。 */
    public synchronized int flush() {
        List<OutboxEvent> batch = outboxStore.pending();
        if (batch.isEmpty()) {
            return 0;
        }
        for (OutboxEvent event : batch) {
            // 模拟 MQ 发送：key 用 aggregateId 保证同一聚合的事件有序
            log.info("[Outbox→MQ] topic={} key={} type={} eventId={} payload={}",
                    TOPIC, event.aggregateId(), event.eventType(), event.eventId(), event.payload());
            event.markDelivered();
        }
        log.info("[Outbox→MQ] 本轮投递 {} 条，剩余待投递 {} 条", batch.size(), outboxStore.pendingCount());
        return batch.size();
    }
}
