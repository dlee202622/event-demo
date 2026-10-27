package com.example.demo.eventdriven.outbox;

/**
 * 发件箱事件状态。
 *
 * <p>PENDING → SENDING（认领）→ SENT（投递成功）；失败时 SENDING → PENDING 等待重试。</p>
 */
public enum OutboxStatus {
    PENDING,
    SENDING,
    SENT
}
