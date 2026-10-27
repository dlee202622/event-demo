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
     * 获取待投递的事件列表。
     */
    List<OutboxEvent> pending();
    
    /**
     * 获取所有事件（用于调试/监控）。
     */
    List<OutboxEvent> findAll();
    
    /**
     * 待投递事件数量。
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
