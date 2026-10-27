package com.example.demo.order.infrastructure.projection.jpa;

import com.example.demo.order.application.query.OrderView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** 订单读模型持久化对象（1:1 对应 order_view）。 */
@Entity
@Table(name = "order_view")
public class OrderViewEntity {

    @Id
    @Column(name = "order_id", length = 32, nullable = false)
    private String orderId;

    @Column(name = "customer_id", length = 64, nullable = false)
    private String customerId;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "status_desc", length = 32, nullable = false)
    private String statusDesc;

    @Column(name = "total_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "currency", length = 8, nullable = false)
    private String currency;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrderViewEntity() {
    }

    static OrderViewEntity from(OrderView view) {
        OrderViewEntity entity = new OrderViewEntity();
        entity.orderId = view.orderId();
        entity.customerId = view.customerId();
        entity.status = view.status();
        entity.statusDesc = view.statusDesc();
        entity.totalAmount = view.totalAmount();
        entity.currency = view.currency();
        entity.itemCount = view.itemCount();
        entity.createdAt = view.createdAt();
        entity.updatedAt = view.updatedAt();
        return entity;
    }

    OrderView toView() {
        return new OrderView(orderId, customerId, status, statusDesc,
                totalAmount, currency, itemCount, createdAt, updatedAt);
    }
}
