package com.example.demo.loyalty.event;

import com.example.demo.loyalty.LoyaltyPointsService;
import com.example.demo.order.domain.event.OrderPaidEvent;
import com.example.demo.eventdriven.idempotency.ProcessedEventRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 积分上下文：订单支付后发放积分（每消费 10 元得 1 分）。
 *
 * <p>这里演示了消费端的两个必备动作：<b>幂等</b> + <b>最小订阅</b>。</p>
 */
@Component
@Order(20)
public class LoyaltyEventListener {

    private static final Logger log = LoggerFactory.getLogger(LoyaltyEventListener.class);

    private final LoyaltyPointsService loyaltyPointsService;
    private final ProcessedEventRegistry processedEventRegistry;

    public LoyaltyEventListener(LoyaltyPointsService loyaltyPointsService,
                               ProcessedEventRegistry processedEventRegistry) {
        this.loyaltyPointsService = loyaltyPointsService;
        this.processedEventRegistry = processedEventRegistry;
    }

    @EventListener
    public void on(OrderPaidEvent event) {
        // 幂等：重复投递直接跳过，避免积分多发
        if (!processedEventRegistry.markIfAbsent(event.eventId())) {
            log.warn("[积分上下文] 重复事件已跳过 eventId={}", event.eventId());
            return;
        }

        int earned = event.paidAmount().amount()
                .divide(BigDecimal.TEN, 0, java.math.RoundingMode.DOWN)
                .intValue();

        int total = loyaltyPointsService.earn(event.customerId(), earned);
        log.info("[积分上下文] 客户 {} 本次 +{} 积分（订单 {}，金额 {}），累计 {}",
                event.customerId(), earned, event.orderId(), event.paidAmount().display(), total);
    }
}
