package com.example.demo.eventdriven.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryProcessedEventRegistryTest {
    
    private InMemoryProcessedEventRegistry registry;
    
    @BeforeEach
    void setUp() {
        registry = new InMemoryProcessedEventRegistry();
    }
    
    @Test
    void markIfAbsent_shouldReturnTrueForFirstTime() {
        assertTrue(registry.markIfAbsent("event-1"));
    }
    
    @Test
    void markIfAbsent_shouldReturnFalseForDuplicate() {
        registry.markIfAbsent("event-1");
        
        assertFalse(registry.markIfAbsent("event-1"));
    }
    
    @Test
    void isProcessed_shouldReturnFalseForNewEvent() {
        assertFalse(registry.isProcessed("event-1"));
    }
    
    @Test
    void isProcessed_shouldReturnTrueAfterMarked() {
        registry.markIfAbsent("event-1");
        
        assertTrue(registry.isProcessed("event-1"));
    }
    
    @Test
    void clear_shouldRemoveAllRecords() {
        registry.markIfAbsent("event-1");
        registry.markIfAbsent("event-2");
        
        registry.clear();
        
        assertFalse(registry.isProcessed("event-1"));
        assertFalse(registry.isProcessed("event-2"));
    }
}
