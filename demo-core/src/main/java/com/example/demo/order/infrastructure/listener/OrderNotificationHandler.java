package com.example.demo.order.infrastructure.listener;

import com.example.demo.order.domain.event.OrderCancelledEvent;
import com.example.demo.order.domain.event.OrderConfirmedEvent;
import com.example.demo.order.domain.event.OrderCreatedEvent;
import com.example.demo.order.domain.event.OrderPaidEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 通知类订阅者：一个订阅者可以同时关心多种事件。
 *
 * <p>它代表「订单上下文内部」的旁路逻辑（发短信、埋点、写审计日志）。
 * 这类逻辑最容易把应用服务写成流水账，用事件订阅剥出来后，
 * 新增一种通知只需要加一个方法，不用动用例代码。</p>
 */
@Component
@Order(30)
public class OrderNotificationHandler {

    private static final Logger log = LoggerFactory.getLogger(OrderNotificationHandler.class);

    @EventListener
    public void on(OrderCreatedEvent event) {
        log.info("[通知] 下单成功短信 → 客户 {}，订单 {}，应付 {}",
                event.customerId(), event.orderId(), event.totalAmount().display());
    }

    @EventListener
    public void on(OrderConfirmedEvent event) {
        log.info("[通知] 订单已确认 → 订单 {}", event.orderId());
    }

    @EventListener
    public void on(OrderPaidEvent event) {
        log.info("[通知] 支付成功回执 → 客户 {}，订单 {}，支付单号 {}",
                event.customerId(), event.orderId(), event.paymentId());
    }

    @EventListener
    public void on(OrderCancelledEvent event) {
        log.info("[通知] 订单已取消 → 订单 {}，原因：{}", event.orderId(), event.reason());
    }
}
