package com.example.demo.eventdriven.outbox;

import com.example.demo.eventdriven.autoconfigure.EventDrivenProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

/**
 * 发件箱中继：定时将待投递事件发送到 RabbitMQ。
 * 
 * <p>关键设计点：</p>
 * <ul>
 *   <li>至少一次投递：失败会重试，消费端必须幂等</li>
 *   <li>顺序性：以 aggregateId 作为 routing key</li>
 *   <li>多实例并发：生产环境应使用数据库锁（SKIP LOCKED）</li>
 * </ul>
 */
public class OutboxRelay {
    
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    
    private final OutboxStore outboxStore;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final boolean relayEnabled;
    private final String exchange;
    private final String routingKeyPrefix;
    
    public OutboxRelay(OutboxStore outboxStore,
                       RabbitTemplate rabbitTemplate,
                       ObjectMapper objectMapper,
                       EventDrivenProperties properties) {
        this.outboxStore = outboxStore;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.relayEnabled = properties.getOutbox().isRelayEnabled();
        this.exchange = properties.getRabbitmq().getExchange();
        this.routingKeyPrefix = properties.getRabbitmq().getRoutingKeyPrefix();
    }
    
    @Scheduled(fixedDelayString = "${eventdriven.outbox.relay-interval-ms:3000}", 
               initialDelayString = "${eventdriven.outbox.relay-initial-delay-ms:2000}")
    public void relay() {
        if (!relayEnabled) {
            return;
        }
        flush();
    }
    
    /**
     * 手动触发一轮投递。
     */
    public synchronized int flush() {
        List<OutboxEvent> batch = outboxStore.pending();
        if (batch.isEmpty()) {
            return 0;
        }
        
        for (OutboxEvent event : batch) {
            try {
                String routingKey = buildRoutingKey(event);
                String message = buildMessage(event);
                
                rabbitTemplate.convertAndSend(
                        exchange,
                        routingKey,
                        message
                );
                
                log.info("[Outbox→RabbitMQ] 发送成功 exchange={} routingKey={} eventType={} eventId={}",
                        exchange, routingKey, event.eventType(), event.eventId());
                event.markDelivered();
                
            } catch (Exception e) {
                log.warn("[Outbox→RabbitMQ] 发送失败，下轮重试 eventId={} eventType={} error={}",
                        event.eventId(), event.eventType(), e.getMessage());
            }
        }
        
        log.info("[Outbox→RabbitMQ] 本轮投递 {} 条，剩余待投递 {} 条", 
                batch.size(), outboxStore.pendingCount());
        return batch.size();
    }
    
    private String buildRoutingKey(OutboxEvent event) {
        return String.format("%s.%s.%s", 
                routingKeyPrefix,
                event.eventType(), 
                event.aggregateId());
    }
    
    private String buildMessage(OutboxEvent event) {
        try {
            return String.format(
                    "{\"eventId\":\"%s\",\"eventType\":\"%s\",\"aggregateId\":\"%s\",\"occurredOn\":\"%s\",\"payload\":%s}",
                    event.eventId(),
                    event.eventType(),
                    event.aggregateId(),
                    event.occurredOn(),
                    event.payload()
            );
        } catch (Exception e) {
            log.error("[Outbox] 消息序列化失败 eventId={}", event.eventId(), e);
            throw new RuntimeException("Failed to serialize message", e);
        }
    }
}
