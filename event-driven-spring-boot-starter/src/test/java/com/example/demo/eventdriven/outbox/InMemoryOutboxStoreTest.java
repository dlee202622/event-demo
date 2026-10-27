package com.example.demo.eventdriven.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryOutboxStoreTest {
    
    private InMemoryOutboxStore store;
    
    @BeforeEach
    void setUp() {
        store = new InMemoryOutboxStore();
    }
    
    @Test
    void append_shouldAddEventToStore() {
        OutboxEvent event = createEvent("event-1", "TestEvent", "agg-1");
        
        store.append(event);
        
        assertEquals(1, store.size());
        assertEquals(1, store.pendingCount());
    }
    
    @Test
    void pending_shouldReturnOnlyUndeliveredEvents() {
        OutboxEvent event1 = createEvent("event-1", "TestEvent", "agg-1");
        OutboxEvent event2 = createEvent("event-2", "TestEvent", "agg-2");
        
        store.append(event1);
        store.append(event2);
        event1.markDelivered();
        
        var pending = store.pending();
        
        assertEquals(1, pending.size());
        assertEquals("event-2", pending.get(0).eventId());
    }
    
    @Test
    void pending_shouldReturnEventsSortedByOccurredOn() {
        Instant now = Instant.now();
        OutboxEvent event1 = new OutboxEvent("1", "event-1", "TestEvent", "agg-1", "{}", now.plusSeconds(2));
        OutboxEvent event2 = new OutboxEvent("2", "event-2", "TestEvent", "agg-2", "{}", now.plusSeconds(1));
        
        store.append(event1);
        store.append(event2);
        
        var pending = store.pending();
        
        assertEquals("event-2", pending.get(0).eventId());
        assertEquals("event-1", pending.get(1).eventId());
    }
    
    @Test
    void clear_shouldRemoveAllEvents() {
        store.append(createEvent("event-1", "TestEvent", "agg-1"));
        store.append(createEvent("event-2", "TestEvent", "agg-2"));
        
        store.clear();
        
        assertEquals(0, store.size());
        assertEquals(0, store.pendingCount());
    }
    
    private OutboxEvent createEvent(String eventId, String eventType, String aggregateId) {
        return new OutboxEvent(
                eventId,
                eventId,
                eventType,
                aggregateId,
                "{}",
                Instant.now()
        );
    }
}
