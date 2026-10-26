package com.example.demo.inventory.event;

import com.example.demo.inventory.InventoryService;
import com.example.demo.order.domain.event.OrderCancelledEvent;
import com.example.demo.order.domain.event.OrderCreatedEvent;
import com.example.demo.order.domain.model.OrderItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 库存上下文的事件订阅者。
 *
 * <p>它引用了订单上下文的「事件契约」（OrderCreatedEvent / OrderCancelledEvent），
 * 但不引用订单的聚合、仓储、应用服务 —— 这是事件驱动最关键的边界感。</p>
 */
@Component
@Order(10)   // 同一事件有多个订阅者时，用 @Order 控制顺序（库存先于通知）
public class InventoryEventListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventListener.class);

    private final InventoryService inventoryService;

    public InventoryEventListener(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @EventListener
    public void on(OrderCreatedEvent event) {
        log.info("[库存上下文] 收到 OrderCreatedEvent orderId={}", event.orderId());
        for (OrderItem item : event.items()) {
            // 真实项目此处若失败，应发布补偿事件（如 StockReservationFailedEvent）驱动订单取消，
            // 而不是直接抛异常回滚上游 —— 上游此刻可能已经提交。
            inventoryService.reserve(item.productId(), item.quantity());
        }
    }

    @EventListener
    public void on(OrderCancelledEvent event) {
        log.info("[库存上下文] 收到 OrderCancelledEvent orderId={} 原因={}", event.orderId(), event.reason());
        for (OrderItem item : event.items()) {
            inventoryService.release(item.productId(), item.quantity());
        }
    }
}
