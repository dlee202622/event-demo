package com.example.demo.inventory;

import com.example.demo.eventdriven.domain.DomainEvent;
import com.example.demo.eventdriven.domain.DomainEventPublisher;
import com.example.demo.inventory.domain.event.InventoryReservationFailedEvent;
import com.example.demo.inventory.domain.event.OrderCancelledEvent;
import com.example.demo.inventory.domain.event.OrderCreatedEvent;
import com.example.demo.inventory.domain.event.OrderItem;
import com.example.demo.inventory.event.InventoryEventListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 库存上下文行为测试：预占、释放、预占失败的补偿事件。
 */
class InventoryEventListenerTest {

    private InventoryService inventoryService;
    private RecordingPublisher publisher;
    private InventoryEventListener listener;

    @BeforeEach
    void setUp() {
        inventoryService = new InventoryService();
        publisher = new RecordingPublisher();
        listener = new InventoryEventListener(inventoryService, publisher);
    }

    @Test
    @DisplayName("下单事件：库存足够时按明细预占")
    void reservesStockWhenEnough() {
        listener.on(orderCreated("ORD-1", new OrderItem("P-1001", 2), new OrderItem("P-1002", 5)));

        assertEquals(98, inventoryService.available("P-1001"));
        assertEquals(45, inventoryService.available("P-1002"));
        assertTrue(publisher.events.isEmpty());
    }

    @Test
    @DisplayName("下单事件：库存不足时发布补偿事件")
    void publishesFailureWhenInsufficient() {
        listener.on(orderCreated("ORD-2", new OrderItem("P-1003", 25)));

        assertEquals(1, publisher.events.size());
        InventoryReservationFailedEvent failure =
                assertInstanceOf(InventoryReservationFailedEvent.class, publisher.events.get(0));
        assertEquals("ORD-2", failure.orderId());
        assertEquals("P-1003", failure.productId());
        assertEquals(25, failure.requiredQuantity());
        assertEquals(20, failure.availableQuantity());
        assertEquals(20, inventoryService.available("P-1003"));
    }

    @Test
    @DisplayName("取消事件：按明细释放库存")
    void releasesStockOnCancel() {
        listener.on(orderCreated("ORD-3", new OrderItem("P-1001", 10)));
        assertEquals(90, inventoryService.available("P-1001"));

        listener.on(orderCancelled("ORD-3", new OrderItem("P-1001", 10)));
        assertEquals(100, inventoryService.available("P-1001"));
    }

    @Test
    @DisplayName("取消事件：预占失败的明细不释放，库存不虚增")
    void doesNotInflateStockWhenReleasingUnreservedItems() {
        listener.on(orderCreated("ORD-4", new OrderItem("P-1003", 25)));
        assertEquals(20, inventoryService.available("P-1003"));

        listener.on(orderCancelled("ORD-4", new OrderItem("P-1003", 25)));
        assertEquals(20, inventoryService.available("P-1003"));
        assertEquals(0, inventoryService.reservedSnapshot().getOrDefault("P-1003", 0));
    }

    @Test
    @DisplayName("取消事件：部分明细预占成功时只释放已预占部分")
    void releasesOnlyReservedPartOnCancel() {
        listener.on(orderCreated("ORD-5", new OrderItem("P-1001", 2), new OrderItem("P-1003", 25)));
        assertEquals(98, inventoryService.available("P-1001"));
        assertEquals(20, inventoryService.available("P-1003"));

        listener.on(orderCancelled("ORD-5", new OrderItem("P-1001", 2), new OrderItem("P-1003", 25)));
        assertEquals(100, inventoryService.available("P-1001"));
        assertEquals(20, inventoryService.available("P-1003"));
    }

    private static OrderCreatedEvent orderCreated(String orderId, OrderItem... items) {
        return new OrderCreatedEvent("evt-" + orderId, orderId, "C-1", List.of(items), Instant.now());
    }

    private static OrderCancelledEvent orderCancelled(String orderId, OrderItem... items) {
        return new OrderCancelledEvent("evt-cancel-" + orderId, orderId, "取消", List.of(items), Instant.now());
    }

    private static final class RecordingPublisher implements DomainEventPublisher {
        private final List<DomainEvent> events = new ArrayList<>();

        @Override
        public void publish(List<? extends DomainEvent> events) {
            this.events.addAll(events);
        }
    }
}
