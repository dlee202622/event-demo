package com.example.demo.shared.infrastructure.outbox;

import com.example.demo.shared.domain.DomainEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 领域事件 → 发件箱。
 *
 * <p>这里做了一次「事件类型转换」：领域事件是限界上下文内部的私有事语言，
 * 而发往 MQ 的集成事件（Integration Event）需要稳定、可向后兼容的契约。
 * 生产中建议在这里把领域事件映射成独立的 {@code XxxIntegrationEvent} DTO，
 * 避免下游因为上游重构内部模型而被迫跟着改。</p>
 */
@Component
public class DomainEventOutboxHandler {

    private final OutboxStore outboxStore;

    public DomainEventOutboxHandler(OutboxStore outboxStore) {
        this.outboxStore = outboxStore;
    }

    /** 监听所有领域事件（参数声明为父接口即可接收全部子类型）。 */
    @EventListener
    public void on(DomainEvent event) {
        outboxStore.append(new OutboxEvent(
                UUID.randomUUID().toString(),
                event.eventId(),
                event.eventType(),
                event.aggregateId(),
                event.toString(),
                event.occurredOn()));
    }
}
