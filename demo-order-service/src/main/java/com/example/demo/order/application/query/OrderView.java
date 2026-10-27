package com.example.demo.order.application.query;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 订单读模型（CQRS 的 Query 侧）。
 *
 * <p>与聚合 {@code Order} 的区别：</p>
 * <ul>
 *   <li>面向查询/展示，字段可以冗余（statusDesc、itemCount）；</li>
 *   <li>由事件投影（Projection）异步/同步维护，没有任何业务行为；</li>
 *   <li>可以独立扩展索引、缓存、甚至异构存储（ES / Redis），不影响写模型。</li>
 * </ul>
 */
public record OrderView(
        String orderId,
        String customerId,
        String status,
        String statusDesc,
        BigDecimal totalAmount,
        String currency,
        int itemCount,
        Instant createdAt,
        Instant updatedAt
) {
}
