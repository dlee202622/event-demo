package com.example.demo.loyalty.domain.event;

import com.example.demo.eventdriven.domain.DomainEvent;

import java.time.Instant;

/**
 * 订单已支付事件（积分上下文本地契约）。
 */
public record OrderPaidEvent(
        String eventId,
        String orderId,
        String customerId,
        Money paidAmount,
        String paymentId,
        Instant occurredOn
) implements DomainEvent {

    @Override
    public String aggregateId() {
        return orderId;
    }

    @Override
    public String eventType() {
        return "OrderPaidEvent";
    }
}
