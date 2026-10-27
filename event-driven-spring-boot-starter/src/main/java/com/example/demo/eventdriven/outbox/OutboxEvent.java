package com.example.demo.eventdriven.outbox;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

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
