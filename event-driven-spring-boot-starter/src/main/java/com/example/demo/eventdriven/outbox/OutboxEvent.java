package com.example.demo.eventdriven.outbox;

import java.time.Instant;

/**
 * 发件箱事件记录。
 *
 * <p>用于实现 Outbox Pattern，保证业务数据落库和事件投递的最终一致性。</p>
 */
public class OutboxEvent {

    private final String id;
    private final String eventId;
    private final String eventType;
    private final String aggregateId;
    private final String payload;
    private final Instant occurredOn;
    private OutboxStatus status;

    public OutboxEvent(String id, String eventId, String eventType, String aggregateId,
                       String payload, Instant occurredOn) {
        this(id, eventId, eventType, aggregateId, payload, occurredOn, OutboxStatus.PENDING);
    }

    public OutboxEvent(String id, String eventId, String eventType, String aggregateId,
                       String payload, Instant occurredOn, OutboxStatus status) {
        this.id = id;
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.occurredOn = occurredOn;
        this.status = status;
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

    public OutboxStatus status() {
        return status;
    }

    public boolean delivered() {
        return status == OutboxStatus.SENT;
    }

    void markSending() {
        this.status = OutboxStatus.SENDING;
    }

    public void markDelivered() {
        this.status = OutboxStatus.SENT;
    }

    public void markFailed() {
        this.status = OutboxStatus.PENDING;
    }

    public String getStatus() {
        return status.name();
    }
}
