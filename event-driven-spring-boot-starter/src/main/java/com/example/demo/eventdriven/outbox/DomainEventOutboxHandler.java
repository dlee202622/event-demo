package com.example.demo.eventdriven.outbox;

import com.example.demo.eventdriven.domain.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import java.util.UUID;

/**
 * 领域事件发件箱处理器。
 * 
 * <p>监听所有领域事件，将其序列化后写入发件箱。</p>
 */
public class DomainEventOutboxHandler {
    
    private static final Logger log = LoggerFactory.getLogger(DomainEventOutboxHandler.class);
    
    private final OutboxStore outboxStore;
    private final ObjectMapper objectMapper;
    
    public DomainEventOutboxHandler(OutboxStore outboxStore, ObjectMapper objectMapper) {
        this.outboxStore = outboxStore;
        this.objectMapper = objectMapper;
    }
    
    @EventListener
    public void on(DomainEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            
            outboxStore.append(new OutboxEvent(
                    UUID.randomUUID().toString(),
                    event.eventId(),
                    event.eventType(),
                    event.aggregateId(),
                    payload,
                    event.occurredOn()
            ));
            
            log.debug("[Outbox] 事件已写入发件箱 eventType={} eventId={}", 
                    event.eventType(), event.eventId());
                    
        } catch (Exception e) {
            log.error("[Outbox] 事件序列化失败 eventType={} eventId={}", 
                    event.eventType(), event.eventId(), e);
        }
    }
}
