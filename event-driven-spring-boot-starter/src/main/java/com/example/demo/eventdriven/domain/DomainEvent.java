package com.example.demo.eventdriven.domain;

import java.time.Instant;

/**
 * 领域事件标记接口。
 * 
 * <p>所有领域事件都应该实现此接口。</p>
 */
public interface DomainEvent {
    
    /**
     * 事件唯一标识。
     */
    String eventId();
    
    /**
     * 事件类型。
     */
    String eventType();
    
    /**
     * 聚合根 ID。
     */
    String aggregateId();
    
    /**
     * 事件发生时间。
     */
    Instant occurredOn();
}
