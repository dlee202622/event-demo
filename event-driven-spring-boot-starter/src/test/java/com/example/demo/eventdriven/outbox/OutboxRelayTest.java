package com.example.demo.eventdriven.outbox;

import com.example.demo.eventdriven.rabbitmq.EventRabbitMQConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxRelayTest {
    
    private InMemoryOutboxStore outboxStore;
    private RabbitTemplate rabbitTemplate;
    private ObjectMapper objectMapper;
    private OutboxRelay relay;
    
    @BeforeEach
    void setUp() {
        outboxStore = new InMemoryOutboxStore();
        rabbitTemplate = mock(RabbitTemplate.class);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        relay = new OutboxRelay(outboxStore, rabbitTemplate, objectMapper, true);
    }
    
    @Test
    void flush_shouldSendPendingEventsToRabbitMQ() {
        OutboxEvent event = new OutboxEvent(
                "id-1", "event-1", "TestEvent", "agg-1",
                "{\"data\":\"test\"}", Instant.now()
        );
        outboxStore.append(event);
        
        int sent = relay.flush();
        
        assertEquals(1, sent);
        verify(rabbitTemplate).convertAndSend(
                eq(EventRabbitMQConfig.EXCHANGE),
                eq("event.TestEvent.agg-1"),
                contains("\"eventId\":\"event-1\"")
        );
        assertTrue(event.delivered());
    }
    
    @Test
    void flush_shouldNotSendAlreadyDeliveredEvents() {
        OutboxEvent event = new OutboxEvent(
                "id-1", "event-1", "TestEvent", "agg-1",
                "{}", Instant.now()
        );
        outboxStore.append(event);
        event.markDelivered();
        
        int sent = relay.flush();
        
        assertEquals(0, sent);
        verifyNoInteractions(rabbitTemplate);
    }
    
    @Test
    void flush_shouldHandleSendFailure() {
        OutboxEvent event = new OutboxEvent(
                "id-1", "event-1", "TestEvent", "agg-1",
                "{}", Instant.now()
        );
        outboxStore.append(event);
        
        doThrow(new RuntimeException("RabbitMQ error"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString());
        
        relay.flush();
        
        // Event should not be marked as delivered when send fails
        assertFalse(event.delivered());
        // Event should still be in pending list
        assertEquals(1, outboxStore.pendingCount());
    }
    
    @Test
    void flush_shouldSendMultipleEvents() {
        outboxStore.append(new OutboxEvent("id-1", "event-1", "TestEvent", "agg-1", "{}", Instant.now()));
        outboxStore.append(new OutboxEvent("id-2", "event-2", "TestEvent", "agg-2", "{}", Instant.now()));
        outboxStore.append(new OutboxEvent("id-3", "event-3", "TestEvent", "agg-3", "{}", Instant.now()));
        
        int sent = relay.flush();
        
        assertEquals(3, sent);
        verify(rabbitTemplate, times(3)).convertAndSend(anyString(), anyString(), anyString());
    }
}
