package com.example.demo.inventory.event;

import com.example.demo.eventdriven.domain.DomainEventPublisher;
import com.example.demo.inventory.InventoryService;
import com.example.demo.inventory.domain.event.InventoryReservationFailedEvent;
import com.example.demo.inventory.domain.event.OrderCancelledEvent;
import com.example.demo.inventory.domain.event.OrderCreatedEvent;
import com.example.demo.inventory.domain.event.OrderItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 库存上下文的事件订阅者。
 *
 * <p>它只认识订单事件的「发布语言」，不引用订单的聚合、仓储、应用服务。
 * 预占失败时发布 {@link InventoryReservationFailedEvent}，驱动订单上下文取消订单。</p>
 */
@Component
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
                String reason = String.format("库存不足：需要 %d，可用 %d", item.quantity(), available);
                log.warn("[库存上下文] 预占失败，发布补偿事件 orderId={} productId={} reason={}",
                        event.orderId(), item.productId(), reason);

                domainEventPublisher.publish(new InventoryReservationFailedEvent(
                        UUID.randomUUID().toString(),
                        event.orderId(),
                        item.productId(),
                        item.quantity(),
                        available,
                        reason,
                        Instant.now()
                ));
                return;
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
