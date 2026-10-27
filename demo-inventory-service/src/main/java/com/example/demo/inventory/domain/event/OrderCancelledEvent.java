package com.example.demo.inventory.domain.event;

import com.example.demo.eventdriven.domain.DomainEvent;

import java.time.Instant;
import java.util.List;

/**
 * 订单已取消事件（库存上下文本地契约）。
 */
public record OrderCancelledEvent(
        String eventId,
        String orderId,
        String reason,
        List<OrderItem> items,
        Instant occurredOn
) implements DomainEvent {

    public OrderCancelledEvent {
        items = items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public String aggregateId() {
        return orderId;
    }

    @Override
    public String eventType() {
        return "OrderCancelledEvent";
    }
}
