package com.example.demo.order.domain.event;

import com.example.demo.eventdriven.domain.DomainEvent;
import java.time.Instant;

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
