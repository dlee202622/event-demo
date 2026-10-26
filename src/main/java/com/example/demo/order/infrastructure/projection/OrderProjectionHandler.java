package com.example.demo.order.infrastructure.projection;

import com.example.demo.order.application.query.OrderView;
import com.example.demo.order.application.query.OrderViewStore;
import com.example.demo.order.domain.event.OrderCancelledEvent;
import com.example.demo.order.domain.event.OrderConfirmedEvent;
import com.example.demo.order.domain.event.OrderCreatedEvent;
import com.example.demo.order.domain.event.OrderPaidEvent;
import com.example.demo.order.domain.model.OrderStatus;
import com.example.demo.shared.domain.DomainEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 读模型投影器（CQRS 的写读桥）。
 *
 * <p>订阅领域事件，把聚合状态投影成面向查询的 {@link OrderView}。
 * 投影器是整个事件驱动架构里「可重放」的部分：清空读模型后，
 * 只要把 {@code DomainEventStore} 里的事件按序重放一遍，读模型就能重建。</p>
 */
@Component
public class OrderProjectionHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderProjectionHandler.class);

    private final OrderViewStore viewStore;

    public OrderProjectionHandler(OrderViewStore viewStore) {
        this.viewStore = viewStore;
    }

    @EventListener
    public void on(OrderCreatedEvent event) {
        viewStore.save(new OrderView(
                event.orderId(),
                event.customerId(),
                OrderStatus.CREATED.name(),
                OrderStatus.CREATED.description(),
                event.totalAmount().amount(),
                event.totalAmount().currency(),
                event.items().size(),
                event.occurredOn(),
                event.occurredOn()));
        log.debug("[投影] 订单读模型已创建 orderId={}", event.orderId());
    }

    @EventListener
    public void on(OrderConfirmedEvent event) {
        updateStatus(event.orderId(), OrderStatus.CONFIRMED, event.occurredOn());
    }

    @EventListener
    public void on(OrderPaidEvent event) {
        updateStatus(event.orderId(), OrderStatus.PAID, event.occurredOn());
    }

    @EventListener
    public void on(OrderCancelledEvent event) {
        updateStatus(event.orderId(), OrderStatus.CANCELLED, event.occurredOn());
    }

    private void updateStatus(String orderId, OrderStatus status, java.time.Instant occurredOn) {
        viewStore.findById(orderId).ifPresentOrElse(
                view -> viewStore.save(new OrderView(
                        view.orderId(),
                        view.customerId(),
                        status.name(),
                        status.description(),
                        view.totalAmount(),
                        view.currency(),
                        view.itemCount(),
                        view.createdAt(),
                        occurredOn)),
                () -> {
                    // 读模型缺失时的兜底：真实项目里通常记录告警并触发全量重建
                    log.warn("[投影] 读模型缺失，跳过状态更新 orderId={} status={}", orderId, status);
                });
    }

    /**
     * 全量重建读模型：清空后按事件流顺序重放。
     *
     * <p>这是事件驱动的最大红利之一：读模型可以随时推倒重来，
     * 不需要从业务库做数据迁移。</p>
     */
    public int rebuild(java.util.List<DomainEvent> eventStream) {
        viewStore.clear();
        int handled = 0;
        for (DomainEvent event : eventStream) {
            if (event instanceof OrderCreatedEvent e) {
                on(e);
                handled++;
            } else if (event instanceof OrderConfirmedEvent e) {
                on(e);
                handled++;
            } else if (event instanceof OrderPaidEvent e) {
                on(e);
                handled++;
            } else if (event instanceof OrderCancelledEvent e) {
                on(e);
                handled++;
            }
        }
        log.info("[投影] 读模型重建完成，重放事件 {} 条", handled);
        return handled;
    }
}
