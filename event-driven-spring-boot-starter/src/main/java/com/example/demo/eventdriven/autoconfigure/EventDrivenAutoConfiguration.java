package com.example.demo.eventdriven.autoconfigure;

import com.example.demo.eventdriven.domain.DomainEventPublisher;
import com.example.demo.eventdriven.idempotency.InMemoryProcessedEventRegistry;
import com.example.demo.eventdriven.idempotency.ProcessedEventRegistry;
import com.example.demo.eventdriven.infrastructure.SpringDomainEventPublisher;
import com.example.demo.eventdriven.outbox.*;
import com.example.demo.eventdriven.rabbitmq.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 事件驱动自动配置。
 */
@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(EventDrivenProperties.class)
@ConditionalOnProperty(prefix = "eventdriven", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EventDrivenAutoConfiguration {
    
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper eventDrivenObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }
    
    @Bean
    @ConditionalOnMissingBean
    public OutboxStore outboxStore() {
        return new InMemoryOutboxStore();
    }
    
    @Bean
    @ConditionalOnMissingBean
    public ProcessedEventRegistry processedEventRegistry() {
        return new InMemoryProcessedEventRegistry();
    }
    
    @Bean
    @ConditionalOnMissingBean
    public DomainEventPublisher domainEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        return new SpringDomainEventPublisher(applicationEventPublisher);
    }
    
    @Bean
    @ConditionalOnMissingBean
    public DomainEventOutboxHandler domainEventOutboxHandler(OutboxStore outboxStore, ObjectMapper objectMapper) {
        return new DomainEventOutboxHandler(outboxStore, objectMapper);
    }
    
    @Bean
    @ConditionalOnMissingBean
    public OutboxRelay outboxRelay(OutboxStore outboxStore, 
                                   RabbitTemplate rabbitTemplate,
                                   ObjectMapper objectMapper,
                                   EventDrivenProperties properties) {
        return new OutboxRelay(
                outboxStore, 
                rabbitTemplate, 
                objectMapper,
                properties
        );
    }
    
    @Bean
    @ConditionalOnMissingBean
    public EventMessageListener eventMessageListener(
            ApplicationEventPublisher applicationEventPublisher,
            ObjectMapper objectMapper,
            ProcessedEventRegistry processedEventRegistry,
            EventConsumer eventConsumer) {
        return new EventMessageListener(
                applicationEventPublisher,
                objectMapper,
                processedEventRegistry,
                eventConsumer
        );
    }
    
    @Bean
    @ConditionalOnMissingBean
    public EventConsumer eventConsumer() {
        // 默认实现：返回 null，需要用户自定义实现
        return (eventId, eventType, aggregateId, payload) -> null;
    }
}
