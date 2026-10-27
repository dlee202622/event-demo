package com.example.demo.infrastructure.event;

import com.example.demo.eventdriven.domain.DomainEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 领域事件审计监听器。
 * 
 * <p>将所有领域事件存储到 DomainEventStore，用于审计、排障和事件溯源。
 * 这与 Outbox 模式不同：Outbox 用于可靠投递到消息队列，而这个监听器用于本地事件存储。</p>
 */
@Component
public class DomainEventAuditListener {
    
    private final DomainEventStore eventStore;
    
    public DomainEventAuditListener(DomainEventStore eventStore) {
        this.eventStore = eventStore;
    }
    
    @EventListener
    public void onDomainEvent(DomainEvent event) {
        eventStore.append(event);
    }
}
