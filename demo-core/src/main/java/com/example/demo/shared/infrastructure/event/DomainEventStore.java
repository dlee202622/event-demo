package com.example.demo.shared.infrastructure.event;

import com.example.demo.eventdriven.domain.DomainEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 领域事件存储（内存版）。
 * 
 * <p>用于审计、排障和事件溯源。生产环境应该使用数据库持久化。</p>
 */
@Component
public class DomainEventStore {
    
    private final Map<String, List<DomainEvent>> eventsByAggregate = new ConcurrentHashMap<>();
    
    /**
     * 存储事件。
     */
    public void append(DomainEvent event) {
        eventsByAggregate
                .computeIfAbsent(event.aggregateId(), k -> new ArrayList<>())
                .add(event);
    }
    
    /**
     * 获取指定聚合根的所有事件。
     */
    public List<DomainEvent> eventsOf(String aggregateId) {
        return eventsByAggregate.getOrDefault(aggregateId, List.of());
    }
    
    /**
     * 获取所有事件。
     */
    public List<DomainEvent> findAll() {
        return eventsByAggregate.values().stream()
                .flatMap(List::stream)
                .toList();
    }
    
    /**
     * 获取所有事件（别名方法）。
     */
    public List<DomainEvent> allEvents() {
        return findAll();
    }
    
    /**
     * 获取事件总数。
     */
    public int size() {
        return findAll().size();
    }
    
    /**
     * 清空所有事件（仅测试用）。
     */
    public void clear() {
        eventsByAggregate.clear();
    }
}
