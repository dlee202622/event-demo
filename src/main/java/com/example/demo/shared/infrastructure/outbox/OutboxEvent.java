package com.example.demo.shared.infrastructure.outbox;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 发件箱记录（Outbox Pattern）。
 *
 * <p>解决的核心问题：<b>「业务数据落库」和「事件投递到 MQ」无法在同一个本地事务里完成</b>。</p>
 * <p>做法：把要发的事件和业务数据写进同一个数据库事务（这里是内存版），
 * 再由独立的中继线程（{@link OutboxRelay}）轮询并投递，投递成功才标记。
 * 即使 MQ 短暂不可用也不会丢事件，重投时靠 eventId 在消费端做幂等。</p>
 */
public class OutboxEvent {

    private final String id;
    private final String eventId;
    private final String eventType;
    private final String aggregateId;
    private final String payload;
    private final Instant occurredOn;
    private final AtomicBoolean delivered = new AtomicBoolean(false);

    public OutboxEvent(String id, String eventId, String eventType, String aggregateId,
                       String payload, Instant occurredOn) {
        this.id = id;
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.occurredOn = occurredOn;
    }

    public String id() {
        return id;
    }

    public String eventId() {
        return eventId;
    }

    public String eventType() {
        return eventType;
    }

    public String aggregateId() {
        return aggregateId;
    }

    public String payload() {
        return payload;
    }

    public Instant occurredOn() {
        return occurredOn;
    }

    public boolean delivered() {
        return delivered.get();
    }

    public void markDelivered() {
        delivered.set(true);
    }

    public String getStatus() {
        return delivered.get() ? "SENT" : "PENDING";
    }
}
