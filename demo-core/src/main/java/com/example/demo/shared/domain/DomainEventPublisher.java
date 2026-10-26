package com.example.demo.shared.domain;

import java.util.List;

/**
 * 领域事件发布端口（Port）。
 *
 * <p>领域层只依赖这个抽象，不关心底下是 Spring 的 ApplicationEventPublisher、
 * Kafka 还是 RocketMQ。这也正是 DDD 里「依赖倒置」的落点：
 * 基础设施层实现端口，领域/应用层依赖端口。</p>
 */
public interface DomainEventPublisher {

    /** 批量发布领域事件（通常来自 {@link AggregateRoot#pullDomainEvents()}）。 */
    void publish(List<? extends DomainEvent> events);

    /** 发布单个事件。 */
    default void publish(DomainEvent event) {
        publish(List.of(event));
    }
}
