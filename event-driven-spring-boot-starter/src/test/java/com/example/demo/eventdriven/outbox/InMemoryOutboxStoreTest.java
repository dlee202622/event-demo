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
        store.append(createEvent("event-1", "TestEvent", "agg-1"));

        assertEquals(1, store.size());
        assertEquals(1, store.pendingCount());
    }

    @Test
    void claimPending_shouldReturnOnlyPendingEventsAndMarkThemSending() {
        OutboxEvent event1 = createEvent("event-1", "TestEvent", "agg-1");
        OutboxEvent event2 = createEvent("event-2", "TestEvent", "agg-2");
        store.append(event1);
        store.append(event2);
        event1.markDelivered();

        var claimed = store.claimPending(10);

        assertEquals(1, claimed.size());
        assertEquals("event-2", claimed.get(0).eventId());
        assertEquals(OutboxStatus.SENDING, event2.status());
    }

    @Test
    void claimPending_shouldReturnEventsSortedByOccurredOnAndRespectLimit() {
        Instant now = Instant.now();
        OutboxEvent event1 = new OutboxEvent("1", "event-1", "TestEvent", "agg-1", "{}", now.plusSeconds(2));
        OutboxEvent event2 = new OutboxEvent("2", "event-2", "TestEvent", "agg-2", "{}", now.plusSeconds(1));
        store.append(event1);
        store.append(event2);

        var claimed = store.claimPending(1);

        assertEquals(1, claimed.size());
        assertEquals("event-2", claimed.get(0).eventId());
        assertEquals(OutboxStatus.PENDING, event1.status());
    }

    @Test
    void markDelivered_shouldUpdateStatusById() {
        OutboxEvent event = createEvent("event-1", "TestEvent", "agg-1");
        store.append(event);
        store.claimPending(10);

        store.markDelivered(event.id());

        assertEquals(OutboxStatus.SENT, event.status());
        assertTrue(event.delivered());
        assertEquals(0, store.pendingCount());
    }

    @Test
    void markFailed_shouldReturnEventToPending() {
        OutboxEvent event = createEvent("event-1", "TestEvent", "agg-1");
        store.append(event);
        store.claimPending(10);

        store.markFailed(event.id());

        assertEquals(OutboxStatus.PENDING, event.status());
        assertEquals(1, store.pendingCount());
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
        return new OutboxEvent(eventId, eventId, eventType, aggregateId, "{}", Instant.now());
    }
}
