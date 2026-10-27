package com.example.demo.eventdriven.idempotency;

/**
 * 已处理事件注册表（幂等性保证）。
 * 
 * <p>生产实现应该使用数据库或 Redis，支持持久化和分布式场景。</p>
 */
public interface ProcessedEventRegistry {
    
    /**
     * 标记事件为已处理。
     * 
     * @param eventId 事件 ID
     * @return true 如果是首次处理，false 如果已处理过
     */
    boolean markIfAbsent(String eventId);
    
    /**
     * 检查事件是否已处理。
     * 
     * @param eventId 事件 ID
     * @return true 如果已处理
     */
    boolean isProcessed(String eventId);
    
    /**
     * 获取已处理事件数量。
     * 
     * @return 已处理事件数
     */
    int size();
    
    /**
     * 清除已处理记录（仅测试用）。
     */
    void clear();
}
