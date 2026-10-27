package com.example.demo.eventdriven.domain;

import java.util.List;

/**
 * 领域事件发布器端口（Port）。
 * 
 * <p>领域层只依赖此抽象，不关心底层是 Spring ApplicationEventPublisher、
 * Kafka 还是 RabbitMQ。</p>
 */
public interface DomainEventPublisher {
    
    /**
     * 批量发布领域事件。
     */
    void publish(List<? extends DomainEvent> events);
    
    /**
     * 发布单个领域事件。
     */
    default void publish(DomainEvent event) {
        publish(List.of(event));
    }
}
