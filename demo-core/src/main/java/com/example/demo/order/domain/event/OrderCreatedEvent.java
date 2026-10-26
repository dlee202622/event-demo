package com.example.demo.order.domain.event;

import com.example.demo.order.domain.model.Money;
import com.example.demo.order.domain.model.OrderItem;
import com.example.demo.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.List;

/**
 * 订单已创建事件。
 *
 * <p>注意事件里携带的是「快照数据」而不是聚合引用：消费者拿到事件即可自行处理，
 * 不需要回调订单服务查询（避免事件驱动退化成同步耦合）。</p>
 */
public record OrderCreatedEvent(
        String eventId,
        String orderId,
        String customerId,
        Money totalAmount,
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
}
