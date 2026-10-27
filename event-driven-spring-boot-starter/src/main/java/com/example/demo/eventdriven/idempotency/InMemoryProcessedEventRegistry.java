package com.example.demo.eventdriven.idempotency;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版已处理事件注册表（默认实现）。
 * 
 * <p>生产环境应该替换为 Redis 或数据库实现。</p>
 */
public class InMemoryProcessedEventRegistry implements ProcessedEventRegistry {
    
    private final Set<String> processedEvents = ConcurrentHashMap.newKeySet();
    
    @Override
    public boolean markIfAbsent(String eventId) {
        return processedEvents.add(eventId);
    }
    
    @Override
    public boolean isProcessed(String eventId) {
        return processedEvents.contains(eventId);
    }
    
    @Override
    public int size() {
        return processedEvents.size();
    }
    
    @Override
    public void clear() {
        processedEvents.clear();
    }
}
