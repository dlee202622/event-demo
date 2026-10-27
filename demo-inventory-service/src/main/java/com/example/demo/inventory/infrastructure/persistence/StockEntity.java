package com.example.demo.inventory.infrastructure.persistence;

import com.example.demo.inventory.domain.Stock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 库存持久化对象（1:1 对应 inventory_stock）。 */
@Entity
@Table(name = "inventory_stock")
public class StockEntity {

    @Id
    @Column(name = "product_id", length = 64, nullable = false)
    private String productId;

    @Column(name = "available", nullable = false)
    private int available;

    @Column(name = "reserved", nullable = false)
    private int reserved;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StockEntity() {
    }

    void apply(Stock stock) {
        this.available = stock.available();
        this.reserved = stock.reserved();
        this.updatedAt = Instant.now();
    }

    Stock toDomain() {
        return new Stock(productId, available, reserved);
    }
}
