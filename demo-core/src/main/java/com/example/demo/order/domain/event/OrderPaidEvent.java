package com.example.demo.order.domain.event;

import com.example.demo.order.domain.model.Money;
import com.example.demo.shared.domain.DomainEvent;

import java.time.Instant;

/**
 * 订单已支付事件。
 *
 * <p>典型的「跨上下文触发点」：积分上下文、履约上下文、通知上下文都只订阅这个事件，
 * 订单上下文完全不知道它们的存在。新增一个下游（比如发票服务）时，订单代码无需改动。</p>
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
}
