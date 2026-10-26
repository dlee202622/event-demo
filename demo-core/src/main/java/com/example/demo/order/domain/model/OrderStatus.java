package com.example.demo.order.domain.model;

/**
 * 订单状态（聚合内的状态机）。
 *
 * <pre>
 *   CREATED ──confirm──▶ CONFIRMED ──pay──▶ PAID
 *      │                     │
 *      └───────cancel────────┴──────▶ CANCELLED
 * </pre>
 *
 * 状态流转规则写在 {@link Order} 里（聚合自身保证一致性），枚举只负责状态语义。
 */
public enum OrderStatus {

    CREATED("已创建"),
    CONFIRMED("已确认"),
    PAID("已支付"),
    CANCELLED("已取消");

    private final String description;

    OrderStatus(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    public boolean isFinal() {
        return this == PAID || this == CANCELLED;
    }
}
