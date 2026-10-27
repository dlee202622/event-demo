package com.example.demo.order.domain.model;

import com.example.demo.eventdriven.domain.DomainException;

/**
 * 订单行（值对象）。
 *
 * <p>只保留「下单那一刻」的商品快照：商品名称与价格已经固化，
 * 后续商品改价不会影响历史订单。这是电商领域非常典型的值对象用法。</p>
 */
public record OrderItem(String productId, String productName, Money unitPrice, int quantity) {

    public OrderItem {
        if (productId == null || productId.isBlank()) {
            throw new DomainException("商品 ID 不能为空");
        }
        if (productName == null || productName.isBlank()) {
            throw new DomainException("商品名称不能为空");
        }
        if (unitPrice == null) {
            throw new DomainException("商品单价不能为空");
        }
        if (quantity <= 0) {
            throw new DomainException("商品数量必须大于 0: " + quantity);
        }
    }

    /** 小计 = 单价 × 数量。 */
    public Money subtotal() {
        return unitPrice.multiply(quantity);
    }
}
