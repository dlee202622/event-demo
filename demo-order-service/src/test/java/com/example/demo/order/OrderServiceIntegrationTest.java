package com.example.demo.order;

import com.example.demo.eventdriven.domain.DomainEvent;
import com.example.demo.eventdriven.idempotency.ProcessedEventRegistry;
import com.example.demo.eventdriven.outbox.OutboxStore;
import com.example.demo.infrastructure.event.DomainEventStore;
import com.example.demo.order.application.OrderApplicationService;
import com.example.demo.order.application.command.PlaceOrderCommand;
import com.example.demo.order.application.query.OrderQueryService;
import com.example.demo.order.application.query.OrderView;
import com.example.demo.order.application.query.OrderViewStore;
import com.example.demo.order.domain.model.OrderId;
import com.example.demo.order.domain.repository.OrderNotFoundException;
import com.example.demo.order.infrastructure.projection.OrderProjectionHandler;
import com.example.demo.eventdriven.domain.DomainException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单服务本地集成测试。
 *
 * <p>只覆盖订单上下文内部行为：聚合不变量、事件登记/发布、Outbox 写入、CQRS 读模型与重建。
 * 库存预占、积分累计等跨服务行为由各自服务的测试与端到端验证覆盖。</p>
 */
@SpringBootTest(properties = {
        "eventdriven.outbox.relay-enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@Testcontainers(disabledWithoutDocker = true)
class OrderServiceIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("demo_order")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private OrderApplicationService orderService;
    @Autowired
    private OrderQueryService queryService;
    @Autowired
    private DomainEventStore eventStore;
    @Autowired
    private OutboxStore outboxStore;
    @Autowired
    private ProcessedEventRegistry processedEventRegistry;
    @Autowired
    private OrderViewStore orderViewStore;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private OrderProjectionHandler projectionHandler;

    @BeforeEach
    void resetAll() {
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM order_view");
        jdbcTemplate.update("DELETE FROM domain_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM processed_event");
    }

    @Test
    @DisplayName("下单 → 确认 → 支付：读模型、事件流、Outbox 全部更新")
    void happyPath() {
        OrderId orderId = orderService.placeOrder(
                command("C-1001", line("P-1001", "机械键盘", "399.00", 2)));

        OrderView created = queryService.findByOrderId(orderId.value());
        assertEquals("CREATED", created.status());
        assertEquals(0, new BigDecimal("798.00").compareTo(created.totalAmount()));
        assertEquals(1, created.itemCount());

        orderService.confirmOrder(orderId.value());
        orderService.payOrder(orderId.value(), "PAY-TEST-001");

        OrderView paid = queryService.findByOrderId(orderId.value());
        assertEquals("PAID", paid.status());
        assertEquals("已支付", paid.statusDesc());

        List<DomainEvent> events = eventStore.eventsOf(orderId.value());
        assertEquals(3, events.size());
        assertEquals("OrderCreatedEvent", events.get(0).eventType());
        assertEquals("OrderConfirmedEvent", events.get(1).eventType());
        assertEquals("OrderPaidEvent", events.get(2).eventType());

        assertEquals(3, outboxStore.pendingCount());
        assertEquals(3, outboxStore.size());
    }

    @Test
    @DisplayName("取消订单：状态变为 CANCELLED，事件流为 Created → Cancelled")
    void cancelOrder() {
        OrderId orderId = orderService.placeOrder(
                command("C-2002", line("P-1003", "显示器支架", "199.00", 5)));

        orderService.cancelOrder(orderId.value(), "用户改主意了");

        assertEquals("CANCELLED", queryService.findByOrderId(orderId.value()).status());
        assertEquals(2, eventStore.eventsOf(orderId.value()).size());
    }

    @Test
    @DisplayName("聚合不变量：重复支付 / 支付已取消订单会被拒绝，且不产生事件")
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

        assertEquals(2, eventStore.eventsOf(orderId.value()).size());
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
    @DisplayName("持久化：聚合、明细、读模型、事件流、Outbox 全部落库")
    void persistsAllStateInMySql() {
        OrderId orderId = orderService.placeOrder(
                command("C-7007", line("P-1001", "机械键盘", "399.00", 2)));

        assertEquals("CREATED", jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId.value()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_items WHERE order_id = ?", Integer.class, orderId.value()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_view WHERE order_id = ?", Integer.class, orderId.value()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_event WHERE aggregate_id = ?", Integer.class, orderId.value()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event", Integer.class));

        // 聚合能从库里重建并继续流转
        orderService.confirmOrder(orderId.value());
        assertEquals("CONFIRMED", jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId.value()));
    }

    @Test
    @DisplayName("下单校验：空商品行、非法订单号、取消不存在的订单")
    void placeOrderValidation() {
        assertThrows(DomainException.class,
                () -> orderService.placeOrder(command("C-6006")));
        assertThrows(DomainException.class, () -> OrderId.of("BAD-ID"));
        assertThrows(OrderNotFoundException.class,
                () -> orderService.cancelOrder("ORD-NOTEXIST", "不存在"));
    }

    private static PlaceOrderCommand command(String customerId, PlaceOrderCommand.Line... lines) {
        return new PlaceOrderCommand(customerId, List.of(lines));
    }

    private static PlaceOrderCommand.Line line(String productId, String name, String price, int quantity) {
        return new PlaceOrderCommand.Line(productId, name, new BigDecimal(price), quantity);
    }
}
