package com.example.demo.eventdriven.outbox;

import java.util.List;

/**
 * 发件箱存储端口。
 *
 * <p>生产实现应该使用数据库表，与业务数据在同一个本地事务中。</p>
 */
public interface OutboxStore {

    /**
     * 添加事件到发件箱。
     */
    void append(OutboxEvent event);

    /**
     * 原子认领待投递事件：PENDING → SENDING。
     *
     * <p>多实例场景下同一条事件只能被一个实例认领。</p>
     */
    List<OutboxEvent> claimPending(int limit);

    /**
     * 投递成功：SENDING → SENT。
     */
    void markDelivered(String outboxId);

    /**
     * 投递失败：SENDING → PENDING，下一轮重试。
     */
    void markFailed(String outboxId);

    /**
     * 获取所有事件（用于调试/监控）。
     */
    List<OutboxEvent> findAll();

    /**
     * 未投递事件数量。
     */
    int pendingCount();

    /**
     * 总事件数量。
     */
    int size();

    /**
     * 清空所有事件（仅测试用）。
     */
    void clear();
}
