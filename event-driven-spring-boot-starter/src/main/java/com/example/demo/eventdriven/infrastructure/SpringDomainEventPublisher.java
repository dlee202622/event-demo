package com.example.demo.eventdriven.infrastructure;

import com.example.demo.eventdriven.domain.DomainEvent;
import com.example.demo.eventdriven.domain.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

/**
 * Spring 领域事件发布器实现。
 * 
 * <p>将领域事件发布到 Spring 事件总线，同时记录到事件存储。</p>
 */
public class SpringDomainEventPublisher implements DomainEventPublisher {
    
    private static final Logger log = LoggerFactory.getLogger(SpringDomainEventPublisher.class);
    
    private final ApplicationEventPublisher applicationEventPublisher;
    
    public SpringDomainEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }
    
    @Override
    public void publish(List<? extends DomainEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        
        for (DomainEvent event : events) {
            log.info("[事件发布] type={} aggregateId={} eventId={}",
                    event.eventType(), event.aggregateId(), event.eventId());
            applicationEventPublisher.publishEvent(event);
        }
    }
}
