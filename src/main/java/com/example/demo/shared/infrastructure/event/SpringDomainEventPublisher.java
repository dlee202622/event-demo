package com.example.demo.shared.infrastructure.event;

import com.example.demo.shared.domain.DomainEvent;
import com.example.demo.shared.domain.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 领域事件发布器（进程内实现）。
 *
 * <p>发布流程：</p>
 * <ol>
 *   <li>写入事件存储（EventStore）——审计 + 回放 + 排障；</li>
 *   <li>交给 Spring 的事件多播器，由 {@code @EventListener} 订阅者消费。</li>
 * </ol>
 *
 * <p>生产环境通常会替换为：
 * Outbox 表（同一本地事务写入）+ 定时/CDC 中继投递到 Kafka/RocketMQ，
 * 以保证「业务数据落库」与「事件投递」的原子性。本 demo 的
 * {@code OutboxRelay} 演示了这套思路的内存版。</p>
 */
@Component
public class SpringDomainEventPublisher implements DomainEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SpringDomainEventPublisher.class);

    private final ApplicationEventPublisher applicationEventPublisher;
    private final DomainEventStore eventStore;

    public SpringDomainEventPublisher(ApplicationEventPublisher applicationEventPublisher,
                                      DomainEventStore eventStore) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.eventStore = eventStore;
    }

    @Override
    public void publish(List<? extends DomainEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        for (DomainEvent event : events) {
            // 1. 先落事件存储，保证即使消费失败也留痕（可重放）
            eventStore.append(event);
            log.info("[事件发布] type={} aggregateId={} eventId={}",
                    event.eventType(), event.aggregateId(), event.eventId());
            // 2. 同步分发给订阅者；订阅者之间互不感知
            applicationEventPublisher.publishEvent(event);
        }
    }
}
