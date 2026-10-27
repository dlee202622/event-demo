package com.example.demo.order.domain.event;

import com.example.demo.eventdriven.domain.DomainEvent;

import java.time.Instant;

/** 订单已确认事件（例如风控通过、库存已锁定）。 */
public record OrderConfirmedEvent(
        String eventId,
        String orderId,
        Instant occurredOn
) implements DomainEvent {

    @Override
    public String aggregateId() {
        return orderId;
    }

    @Override
    public String eventType() {
        return "OrderConfirmedEvent";
    }
}
