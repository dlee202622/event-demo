package com.example.demo.order.domain.event;

import com.example.demo.order.domain.model.OrderItem;
import com.example.demo.shared.domain.DomainEvent;

import java.time.Instant;
import java.util.List;

/**
 * 订单已取消事件（下游据此释放库存、退回优惠券）。
 *
 * <p>特意携带 items 快照：库存上下文要按商品明细释放占用，
 * 如果只带 orderId，它就得反过来同步调用订单服务查询明细 —— 那就不再是事件驱动，
 * 而是伪装成事件的 RPC。事件应当「自解释」。</p>
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
}
