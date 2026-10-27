package com.example.demo.inventory.domain.event;

import com.example.demo.eventdriven.domain.DomainEvent;

import java.time.Instant;
import java.util.List;

/**
 * 订单已创建事件（库存上下文本地契约）。
 *
 * <p>stock-service 只关心商品明细，不依赖订单服务的领域模型。</p>
 */
public record OrderCreatedEvent(
        String eventId,
        String orderId,
        String customerId,
        List<OrderItem> items,
        Instant occurredOn
) implements DomainEvent {

    public OrderCreatedEvent {
        items = items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public String aggregateId() {
        return orderId;
    }

    @Override
    public String eventType() {
        return "OrderCreatedEvent";
    }
}
