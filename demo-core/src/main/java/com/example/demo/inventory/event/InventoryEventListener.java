package com.example.demo.inventory.event;

import com.example.demo.inventory.InventoryService;
import com.example.demo.inventory.domain.event.InventoryReservationFailedEvent;
import com.example.demo.order.domain.event.OrderCancelledEvent;
import com.example.demo.order.domain.event.OrderCreatedEvent;
import com.example.demo.order.domain.model.OrderItem;
import com.example.demo.eventdriven.domain.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 库存上下文的事件订阅者。
 *
 * <p>它引用了订单上下文的「事件契约」（OrderCreatedEvent / OrderCancelledEvent），
 * 但不引用订单的聚合、仓储、应用服务 —— 这是事件驱动最关键的边界感。</p>
 *
 * <p>当库存预占失败时，发布补偿事件 {@link InventoryReservationFailedEvent}，
 * 驱动订单上下文取消订单，实现最终一致性。</p>
 */
@Component
@Order(10)   // 同一事件有多个订阅者时，用 @Order 控制顺序（库存先于通知）
public class InventoryEventListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventListener.class);

    private final InventoryService inventoryService;
    private final DomainEventPublisher domainEventPublisher;

    public InventoryEventListener(InventoryService inventoryService,
                                  DomainEventPublisher domainEventPublisher) {
        this.inventoryService = inventoryService;
        this.domainEventPublisher = domainEventPublisher;
    }

    @EventListener
    public void on(OrderCreatedEvent event) {
        log.info("[库存上下文] 收到 OrderCreatedEvent orderId={}", event.orderId());
        for (OrderItem item : event.items()) {
            int available = inventoryService.available(item.productId());
            boolean success = inventoryService.reserve(item.productId(), item.quantity());
            
            if (!success) {
                // 库存预占失败，发布补偿事件
                String reason = String.format("库存不足：需要 %d，可用 %d", item.quantity(), available);
                log.warn("[库存上下文] 预占失败，发布补偿事件 orderId={} productId={} reason={}", 
                        event.orderId(), item.productId(), reason);
                
                InventoryReservationFailedEvent failureEvent = new InventoryReservationFailedEvent(
                        UUID.randomUUID().toString(),
                        event.orderId(),
                        item.productId(),
                        item.quantity(),
                        available,
                        reason,
                        Instant.now()
                );
                
                domainEventPublisher.publish(failureEvent);
            }
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
