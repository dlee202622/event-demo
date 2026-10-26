package com.example.demo.shared.infrastructure.event;

import com.example.demo.shared.domain.DomainEvent;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件存储（内存版 Event Store）。
 *
 * <p>用途：</p>
 * <ul>
 *   <li>审计：任何状态变化都能追溯到一条事件；</li>
 *   <li>排障：线上「订单为什么变成已取消」可直接查事件流；</li>
 *   <li>回放：重建读模型、重放补偿逻辑；</li>
 *   <li>幂等：消费端按 eventId 去重时，事件存储是天然的已处理清单来源。</li>
 * </ul>
 *
 * <p>生产用 Kafka/RocketMQ + 事件表（或 EventStoreDB）实现，本类只做演示。</p>
 */
@Component
public class DomainEventStore {

    /** 全局有序事件流（顺序号用于跨聚合排序）。 */
    private record StoredEvent(long sequence, DomainEvent event) {
    }

    private final List<StoredEvent> stream = new CopyOnWriteArrayList<>();
    private final Map<String, List<StoredEvent>> byAggregate = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    /** 追加一条事件。 */
    public void append(DomainEvent event) {
        StoredEvent stored = new StoredEvent(sequence.incrementAndGet(), event);
        stream.add(stored);
        byAggregate.computeIfAbsent(event.aggregateId(), key -> new CopyOnWriteArrayList<>()).add(stored);
    }

    /** 某个聚合的完整事件流，按发生顺序返回。 */
    public List<DomainEvent> eventsOf(String aggregateId) {
        return byAggregate.getOrDefault(aggregateId, List.of()).stream()
                .map(StoredEvent::event)
                .toList();
    }

    /** 全局事件流（可用于重放读模型）。 */
    public List<DomainEvent> allEvents() {
        return Collections.unmodifiableList(stream.stream().map(StoredEvent::event).toList());
    }

    /** 已记录事件总数。 */
    public int size() {
        return stream.size();
    }

    /** 仅测试/演示用。 */
    public void clear() {
        stream.clear();
        byAggregate.clear();
        sequence.set(0);
    }
}
