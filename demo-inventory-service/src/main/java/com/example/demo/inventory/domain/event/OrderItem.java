package com.example.demo.inventory.domain.event;

/**
 * 订单商品行（库存上下文视角）。
 *
 * <p>库存服务只需要知道「哪个商品、多少件」，不需要价格、名称等订单侧字段。
 * 这是消费方按需定义事件契约的最小化原则。</p>
 */
public record OrderItem(String productId, int quantity) {
}
