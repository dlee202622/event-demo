package com.example.demo.order.infrastructure.persistence.jpa;

import com.example.demo.order.domain.model.Money;
import com.example.demo.order.domain.model.OrderItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** 订单行持久化对象（1:1 对应 order_items）。 */
@Entity
@Table(name = "order_items")
public class OrderItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "product_id", length = 64, nullable = false)
    private String productId;

    @Column(name = "product_name", length = 128, nullable = false)
    private String productName;

    @Column(name = "unit_price", precision = 12, scale = 2, nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "currency", length = 8, nullable = false)
    private String currency;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected OrderItemEntity() {
    }

    static OrderItemEntity from(int lineNo, OrderItem item) {
        OrderItemEntity entity = new OrderItemEntity();
        entity.lineNo = lineNo;
        entity.productId = item.productId();
        entity.productName = item.productName();
        entity.unitPrice = item.unitPrice().amount();
        entity.currency = item.unitPrice().currency();
        entity.quantity = item.quantity();
        return entity;
    }

    OrderItem toDomain() {
        return new OrderItem(productId, productName, new Money(unitPrice, currency), quantity);
    }
}
