package com.example.demo.eventdriven.outbox;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版发件箱存储（默认实现）。
 *
 * <p>生产环境应该替换为 {@link JdbcOutboxStore}，使用本地事务保证原子性。</p>
 */
public class InMemoryOutboxStore implements OutboxStore {

    private final Map<String, OutboxEvent> events = new ConcurrentHashMap<>();

    @Override
    public void append(OutboxEvent event) {
        events.put(event.id(), event);
    }

    @Override
    public synchronized List<OutboxEvent> claimPending(int limit) {
        List<OutboxEvent> claimed = events.values().stream()
                .filter(event -> event.status() == OutboxStatus.PENDING)
                .sorted(Comparator.comparing(OutboxEvent::occurredOn)
                        .thenComparing(OutboxEvent::id))
                .limit(limit)
                .toList();
        claimed.forEach(OutboxEvent::markSending);
        return claimed;
    }

    @Override
    public void markDelivered(String outboxId) {
        OutboxEvent event = events.get(outboxId);
        if (event != null) {
            event.markDelivered();
        }
    }

    @Override
    public void markFailed(String outboxId) {
        OutboxEvent event = events.get(outboxId);
        if (event != null) {
            event.markFailed();
        }
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
                .filter(event -> event.status() != OutboxStatus.SENT)
                .count();
    }

    @Override
    public int size() {
        return events.size();
    }

    @Override
    public void clear() {
        events.clear();
    }
}
