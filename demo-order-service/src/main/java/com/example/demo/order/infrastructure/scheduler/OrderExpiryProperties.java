package com.example.demo.order.infrastructure.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 超时未支付清理的配置。 */
@ConfigurationProperties(prefix = "demo.order")
public class OrderExpiryProperties {

    /** 超过该时长仍未支付则自动取消。 */
    private Duration unpaidTimeout = Duration.ofMinutes(30);

    /** 单次调度最多处理的订单数。 */
    private int expiryBatchSize = 100;

    public Duration getUnpaidTimeout() {
        return unpaidTimeout;
    }

    public void setUnpaidTimeout(Duration unpaidTimeout) {
        this.unpaidTimeout = unpaidTimeout;
    }

    public int getExpiryBatchSize() {
        return expiryBatchSize;
    }

    public void setExpiryBatchSize(int expiryBatchSize) {
        this.expiryBatchSize = expiryBatchSize;
    }
}
