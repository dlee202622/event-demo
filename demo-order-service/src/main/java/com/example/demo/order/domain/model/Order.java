package com.example.demo.order.domain.model;

import com.example.demo.order.domain.event.OrderCancelledEvent;
import com.example.demo.order.domain.event.OrderConfirmedEvent;
import com.example.demo.order.domain.event.OrderCreatedEvent;
import com.example.demo.order.domain.event.OrderPaidEvent;
import com.example.demo.eventdriven.domain.AggregateRoot;
import com.example.demo.eventdriven.domain.DomainException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 订单聚合根（Aggregate Root）。
 *
 * <p>聚合的三条铁律在本类中的体现：</p>
 * <ol>
 *   <li><b>一致性边界</b>：所有状态变更都走聚合方法，外部不能直接 setter；</li>
 *   <li><b>不变量自守</b>：金额 = 各行小计之和、状态机合法流转，都在聚合内校验；</li>
 *   <li><b>事件登记</b>：任何状态变化都登记一个领域事件，由应用层统一发布，
 *       领域层不依赖 Spring。</li>
 * </ol>
 */
public class Order extends AggregateRoot {

    private final OrderId id;
    private final String customerId;
    private final List<OrderItem> items;
    private final Instant createdAt;

    private OrderStatus status;
    private Money totalAmount;
    private String paymentId;
    private Instant updatedAt;

    private Order(OrderId id, String customerId, List<OrderItem> items, OrderStatus status,
                  Money totalAmount, String paymentId, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.customerId = customerId;
        this.items = List.copyOf(items);
        this.status = status;
        this.totalAmount = totalAmount;
        this.paymentId = paymentId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // ------------------------------------------------------------------
    // 工厂方法：创建聚合的唯一入口，保证创建即合法
    // ------------------------------------------------------------------

    /** 下单。 */
    public static Order place(OrderId id, String customerId, List<OrderItem> items) {
        if (customerId == null || customerId.isBlank()) {
            throw new DomainException("客户 ID 不能为空");
        }
        if (items == null || items.isEmpty()) {
            throw new DomainException("订单至少需要包含一个商品");
        }
        Money total = items.stream()
                .map(OrderItem::subtotal)
                .reduce(Money.ZERO, Money::add);

        Instant now = Instant.now();
        Order order = new Order(id, customerId, items, OrderStatus.CREATED, total, null, now, now);

        order.registerEvent(new OrderCreatedEvent(
                UUID.randomUUID().toString(),
                id.value(),
                customerId,
                total,
                order.items,
                now));
        return order;
    }

    // ------------------------------------------------------------------
    // 业务行为：每个动作 = 校验 + 改状态 + 登记事件
    // ------------------------------------------------------------------

    /** 确认订单。 */
    public void confirm() {
        requireStatus(OrderStatus.CREATED, "确认");
        this.status = OrderStatus.CONFIRMED;
        this.updatedAt = Instant.now();
        registerEvent(new OrderConfirmedEvent(UUID.randomUUID().toString(), id.value(), updatedAt));
    }

    /** 支付订单。 */
    public void pay(String paymentId) {
        if (paymentId == null || paymentId.isBlank()) {
            throw new DomainException("支付流水号不能为空");
        }
        if (this.status == OrderStatus.PAID) {
            throw new DomainException("订单已支付，不能重复支付: " + id.value());
        }
        if (this.status == OrderStatus.CANCELLED) {
            throw new DomainException("订单已取消，不能支付: " + id.value());
        }
        this.status = OrderStatus.PAID;
        this.paymentId = paymentId;
        this.updatedAt = Instant.now();
        registerEvent(new OrderPaidEvent(
                UUID.randomUUID().toString(),
                id.value(),
                customerId,
                totalAmount,
                paymentId,
                updatedAt));
    }

    /** 取消订单。 */
    public void cancel(String reason) {
        if (this.status == OrderStatus.CANCELLED) {
            throw new DomainException("订单已取消，无需重复操作: " + id.value());
        }
        if (this.status == OrderStatus.PAID) {
            throw new DomainException("订单已支付，需先走退款流程再取消: " + id.value());
        }
        this.status = OrderStatus.CANCELLED;
        this.updatedAt = Instant.now();
        registerEvent(new OrderCancelledEvent(
                UUID.randomUUID().toString(),
                id.value(),
                reason == null || reason.isBlank() ? "用户取消" : reason,
                items,
                updatedAt));
    }

    // ------------------------------------------------------------------
    // 仓储重建：从持久化数据恢复聚合，不产生新事件
    // ------------------------------------------------------------------

    public static Order rehydrate(OrderId id, String customerId, List<OrderItem> items,
                                  OrderStatus status, Money totalAmount, String paymentId,
                                  Instant createdAt, Instant updatedAt) {
        return new Order(id, customerId, items, status, totalAmount, paymentId, createdAt, updatedAt);
    }

    // ------------------------------------------------------------------
    // 查询语义（只读，不改变状态，不发事件）
    // ------------------------------------------------------------------

    public OrderId id() {
        return id;
    }

    public String customerId() {
        return customerId;
    }

    public List<OrderItem> items() {
        return items;
    }

    public OrderStatus status() {
        return status;
    }

    public Money totalAmount() {
        return totalAmount;
    }

    public String paymentId() {
        return paymentId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public int itemCount() {
        return items.size();
    }

    private void requireStatus(OrderStatus expected, String action) {
        if (this.status != expected) {
            throw new DomainException("当前状态[" + status.description() + "]不允许" + action
                    + "，期望状态[" + expected.description() + "]");
        }
    }
}
