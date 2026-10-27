package com.example.demo.order.infrastructure.persistence.jpa;

import com.example.demo.order.domain.model.Money;
import com.example.demo.order.domain.model.Order;
import com.example.demo.order.domain.model.OrderId;
import com.example.demo.order.domain.model.OrderItem;
import com.example.demo.order.domain.model.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 订单聚合的持久化对象。
 *
 * <p>领域类 {@link Order} 保持零框架注解，映射逻辑收敛在这里。
 * 订单行在聚合生命周期内不可变、数量很少，因此 EAGER 加载，避免映射发生在事务边界之外。</p>
 */
@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    @Column(name = "id", length = 32, nullable = false)
    private String id;

    @Column(name = "customer_id", length = 64, nullable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private OrderStatus status;

    @Column(name = "total_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "currency", length = 8, nullable = false)
    private String currency;

    @Column(name = "payment_id", length = 64)
    private String paymentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id", nullable = false)
    @OrderBy("lineNo ASC")
    private List<OrderItemEntity> items = new ArrayList<>();

    protected OrderEntity() {
    }

    static OrderEntity from(Order order) {
        OrderEntity entity = new OrderEntity();
        entity.id = order.id().value();
        entity.applyState(order);
        for (int index = 0; index < order.items().size(); index++) {
            entity.items.add(OrderItemEntity.from(index + 1, order.items().get(index)));
        }
        return entity;
    }

    void applyState(Order order) {
        this.customerId = order.customerId();
        this.status = order.status();
        this.totalAmount = order.totalAmount().amount();
        this.currency = order.totalAmount().currency();
        this.paymentId = order.paymentId();
        this.createdAt = order.createdAt();
        this.updatedAt = order.updatedAt();
    }

    Order toDomain() {
        List<OrderItem> domainItems = items.stream()
                .map(OrderItemEntity::toDomain)
                .toList();
        return Order.rehydrate(
                OrderId.of(id),
                customerId,
                domainItems,
                status,
                new Money(totalAmount, currency),
                paymentId,
                createdAt,
                updatedAt);
    }
}
