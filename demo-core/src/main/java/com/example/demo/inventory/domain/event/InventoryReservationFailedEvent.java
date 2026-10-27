package com.example.demo.inventory.domain.event;

import com.example.demo.eventdriven.domain.DomainEvent;

import java.time.Instant;

/**
 * 库存预占失败事件。
 *
 * <p>当库存服务无法预占库存时发布此事件，驱动订单上下文取消订单。
 * 这是事件驱动架构中的「补偿机制」典型应用。</p>
 */
public record InventoryReservationFailedEvent(
        String eventId,
        String orderId,
        String productId,
        int requiredQuantity,
        int availableQuantity,
        String reason,
        Instant occurredOn
) implements DomainEvent {

    @Override
    public String aggregateId() {
        return orderId;
    }

    @Override
    public String eventType() {
        return "InventoryReservationFailedEvent";
    }
}
