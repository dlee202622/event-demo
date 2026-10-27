package com.example.demo.loyalty.domain.event;

import java.math.BigDecimal;

/**
 * 金额（积分上下文视角：只需要数值，币种仅作展示）。
 */
public record Money(BigDecimal amount, String currency) {
}
