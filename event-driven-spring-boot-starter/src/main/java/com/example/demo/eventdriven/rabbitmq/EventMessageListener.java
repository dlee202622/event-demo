package com.example.demo.eventdriven.rabbitmq;

import com.example.demo.eventdriven.domain.DomainEvent;
import com.example.demo.eventdriven.idempotency.ProcessedEventRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;

/**
 * RabbitMQ 事件消息监听器。
 * 
 * <p>接收消息后反序列化为领域事件，重新发布到 Spring 事件总线。</p>
 */
public class EventMessageListener {
    
    private static final Logger log = LoggerFactory.getLogger(EventMessageListener.class);
    
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRegistry processedEventRegistry;
    private final EventConsumer eventConsumer;
    
    public EventMessageListener(ApplicationEventPublisher eventPublisher,
                                ObjectMapper objectMapper,
                                ProcessedEventRegistry processedEventRegistry,
                                EventConsumer eventConsumer) {
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.processedEventRegistry = processedEventRegistry;
        this.eventConsumer = eventConsumer;
    }
    
    @RabbitListener(queues = EventRabbitMQConfig.QUEUE)
    public void onMessage(String message) {
        try {
            log.debug("[RabbitMQ→Spring] 收到消息: {}", message);
            
            JsonNode root = objectMapper.readTree(message);
            String eventId = root.get("eventId").asText();
            String eventType = root.get("eventType").asText();
            String aggregateId = root.get("aggregateId").asText();
            String occurredOnStr = root.get("occurredOn").asText();
            JsonNode payloadNode = root.get("payload");
            
            // 幂等检查
            if (!processedEventRegistry.markIfAbsent(eventId)) {
                log.info("[RabbitMQ→Spring] 事件已处理，跳过 eventId={} eventType={}", eventId, eventType);
                return;
            }
            
            Instant occurredOn = Instant.parse(occurredOnStr);
            
            // 反序列化事件
            DomainEvent domainEvent = eventConsumer.deserialize(
                    eventId, eventType, aggregateId, payloadNode);
            
            if (domainEvent != null) {
                eventPublisher.publishEvent(domainEvent);
                log.info("[RabbitMQ→Spring] 事件已发布 eventType={} eventId={}", eventType, eventId);
            } else {
                log.warn("[RabbitMQ→Spring] 未知事件类型 eventType={}", eventType);
            }
            
        } catch (Exception e) {
            log.error("[RabbitMQ→Spring] 消息处理失败: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to process message", e);
        }
    }
}
