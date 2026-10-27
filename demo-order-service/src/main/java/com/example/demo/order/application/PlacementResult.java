package com.example.demo.order.application;

/**
 * 下单等待窗口的结果。
 *
 * <p>库存预占是异步的（Saga 补偿）：窗口内收到取消补偿说明预占失败，
 * 否则按成功处理。</p>
 */
public enum PlacementResult {
    ACCEPTED,
    CANCELLED
}
