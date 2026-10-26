package com.example.demo.order;

import com.example.demo.inventory.InventoryService;
import com.example.demo.loyalty.LoyaltyPointsService;
import com.example.demo.loyalty.event.LoyaltyEventListener;
import com.example.demo.order.application.OrderApplicationService;
import com.example.demo.order.application.command.PlaceOrderCommand;
import com.example.demo.order.application.query.OrderQueryService;
import com.example.demo.order.application.query.OrderView;
import com.example.demo.order.application.query.OrderViewStore;
import com.example.demo.order.domain.event.OrderPaidEvent;
import com.example.demo.order.domain.model.OrderId;
import com.example.demo.order.domain.repository.OrderNotFoundException;
import com.example.demo.order.infrastructure.persistence.InMemoryOrderRepository;
import com.example.demo.order.infrastructure.projection.OrderProjectionHandler;
import com.example.demo.shared.domain.DomainEvent;
import com.example.demo.shared.domain.DomainException;
import com.example.demo.shared.infrastructure.event.DomainEventStore;
import com.example.demo.shared.infrastructure.idempotency.ProcessedEventRegistry;
import com.example.demo.shared.infrastructure.outbox.OutboxRelay;
import com.example.demo.shared.infrastructure.outbox.OutboxStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件驱动链路集成测试。
 *
 * <p>覆盖点：聚合不变量、事件发布、下游上下文消费、CQRS 读模型、Outbox 投递、消费幂等、读模型重建。</p>
 */
@SpringBootTest(properties = "demo.outbox.relay-enabled=false")
class OrderFlowIntegrationTest {

    @Autowired
    private OrderApplicationService orderService;
    @Autowired
    private OrderQueryService queryService;
    @Autowired
    private DomainEventStore eventStore;
    @Autowired
    private OutboxStore outboxStore;
    @Autowired
    private OutboxRelay outboxRelay;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private LoyaltyPointsService loyaltyPointsService;
    @Autowired
    private LoyaltyEventListener loyaltyEventListener;
    @Autowired
    private ProcessedEventRegistry processedEventRegistry;
    @Autowired
    private OrderViewStore orderViewStore;
    @Autowired
    private InMemoryOrderRepository orderRepository;
    @Autowired
    private OrderProjectionHandler projectionHandler;

    @BeforeEach
    void resetAll() {
        orderRepository.clear();
        orderViewStore.clear();
        eventStore.clear();
        outboxStore.clear();
        inventoryService.reset();
        loyaltyPointsService.reset();
        processedEventRegistry.clear();
    }

    @Test
    @DisplayName("下单 → 确认 → 支付：事件驱动库存、积分、读模型、Outbox")
    void fullHappyPath() {
        OrderId orderId = orderService.placeOrder(
                command("C-1001", line("P-1001", "机械键盘", "399.00", 2)));

        OrderView created = queryService.findByOrderId(orderId.value());
        assertEquals("CREATED", created.status());
        assertEquals(0, new BigDecimal("798.00").compareTo(created.totalAmount()));
        assertEquals(1, created.itemCount());
        // 库存已被 OrderCreatedEvent 的订阅者预占
        assertEquals(98, inventoryService.available("P-1001"));

        orderService.confirmOrder(orderId.value());
        orderService.payOrder(orderId.value(), "PAY-TEST-001");

        OrderView paid = queryService.findByOrderId(orderId.value());
        assertEquals("PAID", paid.status());
        assertEquals("已支付", paid.statusDesc());
        // 798 / 10 = 79 积分
        assertEquals(79, loyaltyPointsService.pointsOf("C-1001"));

        // 事件流：Created → Confirmed → Paid
        List<DomainEvent> events = eventStore.eventsOf(orderId.value());
        assertEquals(3, events.size());
        assertEquals("OrderCreatedEvent", events.get(0).eventType());
        assertEquals("OrderConfirmedEvent", events.get(1).eventType());
        assertEquals("OrderPaidEvent", events.get(2).eventType());

        // 发件箱：3 条待投递，flush 后全部投递
        assertEquals(3, outboxStore.pendingCount());
        assertEquals(3, outboxRelay.flush());
        assertEquals(0, outboxStore.pendingCount());
        assertEquals(3, outboxStore.size());
    }

    @Test
    @DisplayName("取消订单：库存被释放，状态变为 CANCELLED")
    void cancelReleasesInventory() {
        OrderId orderId = orderService.placeOrder(
                command("C-2002", line("P-1003", "显示器支架", "199.00", 5)));

        assertEquals(15, inventoryService.available("P-1003"));

        orderService.cancelOrder(orderId.value(), "用户改主意了");

        assertEquals("CANCELLED", queryService.findByOrderId(orderId.value()).status());
        assertEquals(20, inventoryService.available("P-1003"));
        assertEquals(2, eventStore.eventsOf(orderId.value()).size());
    }

    @Test
    @DisplayName("聚合不变量：重复支付 / 支付已取消订单会被拒绝")
    void aggregateInvariants() {
        OrderId orderId = orderService.placeOrder(
                command("C-3003", line("P-1002", "人体工学椅", "1299.00", 1)));
        orderService.payOrder(orderId.value(), "PAY-TEST-002");

        DomainException repeatPay = assertThrows(DomainException.class,
                () -> orderService.payOrder(orderId.value(), "PAY-TEST-003"));
        assertTrue(repeatPay.getMessage().contains("不能重复支付"));

        DomainException cancelPaid = assertThrows(DomainException.class,
                () -> orderService.cancelOrder(orderId.value(), "想退款"));
        assertTrue(cancelPaid.getMessage().contains("退款"));

        // 事件没有被"失败的操作"污染
        assertEquals(2, eventStore.eventsOf(orderId.value()).size());
    }

    @Test
    @DisplayName("消费幂等：同一事件重复投递不会重复发积分")
    void consumerIdempotency() {
        OrderId orderId = orderService.placeOrder(
                command("C-4004", line("P-1001", "机械键盘", "399.00", 1)));
        orderService.payOrder(orderId.value(), "PAY-TEST-004");

        int pointsAfterFirstDelivery = loyaltyPointsService.pointsOf("C-4004");
        assertEquals(39, pointsAfterFirstDelivery);

        OrderPaidEvent paidEvent = eventStore.eventsOf(orderId.value()).stream()
                .filter(OrderPaidEvent.class::isInstance)
                .map(OrderPaidEvent.class::cast)
                .findFirst()
                .orElseThrow();

        // 模拟 MQ 重复投递
        loyaltyEventListener.on(paidEvent);
        loyaltyEventListener.on(paidEvent);

        assertEquals(pointsAfterFirstDelivery, loyaltyPointsService.pointsOf("C-4004"));
    }

    @Test
    @DisplayName("读模型可重建：清空后按事件流重放即可恢复")
    void projectionRebuild() {
        OrderId orderId = orderService.placeOrder(
                command("C-5005", line("P-1002", "人体工学椅", "1299.00", 1)));
        orderService.confirmOrder(orderId.value());
        orderService.payOrder(orderId.value(), "PAY-TEST-005");

        orderViewStore.clear();
        assertThrows(OrderQueryService.OrderViewNotFoundException.class,
                () -> queryService.findByOrderId(orderId.value()));

        int replayed = projectionHandler.rebuild(eventStore.allEvents());
        assertEquals(3, replayed);

        OrderView rebuilt = queryService.findByOrderId(orderId.value());
        assertEquals("PAID", rebuilt.status());
        assertEquals(0, new BigDecimal("1299.00").compareTo(rebuilt.totalAmount()));
    }

    @Test
    @DisplayName("下单校验：空商品行、非法订单号")
    void placeOrderValidation() {
        assertThrows(DomainException.class,
                () -> orderService.placeOrder(command("C-6006")));
        assertThrows(DomainException.class, () -> OrderId.of("BAD-ID"));
        assertThrows(OrderNotFoundException.class,
                () -> orderService.cancelOrder("ORD-NOTEXIST", "不存在"));
    }

    // ------------------------------------------------------------------

    private static PlaceOrderCommand command(String customerId, PlaceOrderCommand.Line... lines) {
        return new PlaceOrderCommand(customerId, List.of(lines));
    }

    private static PlaceOrderCommand.Line line(String productId, String name, String price, int quantity) {
        return new PlaceOrderCommand.Line(productId, name, new BigDecimal(price), quantity);
    }
}
