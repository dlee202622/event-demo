package com.example.demo.shared.infrastructure.outbox;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 发件箱存储（内存版）。
 *
 * <p>生产实现：一张 outbox 表，字段与 {@link OutboxEvent} 一一对应，
 * 与业务表在同一个本地事务里 INSERT；中继使用
 * {@code SELECT ... WHERE status='PENDING' ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED}
 * 做并发安全的多实例抢占投递。</p>
 */
@Component
public class OutboxStore {

    private final Map<String, OutboxEvent> events = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public void append(OutboxEvent event) {
        events.put(event.id(), event);
        sequence.incrementAndGet();
    }

    /** 待投递事件，按写入顺序（模拟 outbox 表按自增 id 顺序投递）。 */
    public List<OutboxEvent> pending() {
        return events.values().stream()
                .filter(event -> !event.delivered())
                .sorted(Comparator.comparing(OutboxEvent::occurredOn).thenComparing(OutboxEvent::eventId))
                .toList();
    }

    public List<OutboxEvent> findAll() {
        return events.values().stream()
                .sorted(Comparator.comparing(OutboxEvent::occurredOn))
                .toList();
    }

    public int pendingCount() {
        return (int) events.values().stream().filter(event -> !event.delivered()).count();
    }

    public int size() {
        return events.size();
    }

    public void clear() {
        events.clear();
        sequence.set(0);
    }
}
