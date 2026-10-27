package com.example.demo.eventdriven.outbox;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存版发件箱存储（默认实现）。
 * 
 * <p>生产环境应该替换为数据库实现，使用本地事务保证原子性。</p>
 */
public class InMemoryOutboxStore implements OutboxStore {
    
    private final Map<String, OutboxEvent> events = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    
    @Override
    public void append(OutboxEvent event) {
        events.put(event.id(), event);
        sequence.incrementAndGet();
    }
    
    @Override
    public List<OutboxEvent> pending() {
        return events.values().stream()
                .filter(event -> !event.delivered())
                .sorted(Comparator.comparing(OutboxEvent::occurredOn)
                        .thenComparing(OutboxEvent::eventId))
                .toList();
    }
    
    @Override
    public List<OutboxEvent> findAll() {
        return events.values().stream()
                .sorted(Comparator.comparing(OutboxEvent::occurredOn))
                .toList();
    }
    
    @Override
    public int pendingCount() {
        return (int) events.values().stream()
                .filter(event -> !event.delivered())
                .count();
    }
    
    @Override
    public int size() {
        return events.size();
    }
    
    @Override
    public void clear() {
        events.clear();
        sequence.set(0);
    }
}
