package com.example.demo.loyalty.event;

import com.example.demo.loyalty.LoyaltyPointsService;
import com.example.demo.loyalty.domain.event.OrderPaidEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 积分上下文：订单支付后发放积分（每消费 10 元得 1 分）。
 *
 * <p>幂等由 starter 的 EventMessageListener 在消息入口统一保证（按 eventId 去重），
 * 这里不再重复标记，否则同一 eventId 会被二次拦截导致积分漏发。</p>
 */
@Component
public class LoyaltyEventListener {

    private static final Logger log = LoggerFactory.getLogger(LoyaltyEventListener.class);

    private final LoyaltyPointsService loyaltyPointsService;

    public LoyaltyEventListener(LoyaltyPointsService loyaltyPointsService) {
        this.loyaltyPointsService = loyaltyPointsService;
    }

    @EventListener
    public void on(OrderPaidEvent event) {
        int earned = event.paidAmount().amount()
                .divide(BigDecimal.TEN, 0, RoundingMode.DOWN)
                .intValue();

        int total = loyaltyPointsService.earn(event.customerId(), earned);
        log.info("[积分上下文] 客户 {} 本次 +{} 积分（订单 {}，金额 {}），累计 {}",
                event.customerId(), earned, event.orderId(), event.paidAmount().amount(), total);
    }
}
