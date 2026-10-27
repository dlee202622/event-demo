package com.example.demo.eventdriven.autoconfigure;

import com.example.demo.eventdriven.domain.DomainEventPublisher;
import com.example.demo.eventdriven.idempotency.InMemoryProcessedEventRegistry;
import com.example.demo.eventdriven.idempotency.JdbcProcessedEventRegistry;
import com.example.demo.eventdriven.idempotency.ProcessedEventRegistry;
import com.example.demo.eventdriven.infrastructure.SpringDomainEventPublisher;
import com.example.demo.eventdriven.outbox.*;
import com.example.demo.eventdriven.rabbitmq.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/**
 * 事件驱动自动配置。
 *
 * <p>Outbox / 幂等存储有两种实现：默认内存版；当 eventdriven.persistence=jdbc
 * 且容器里存在 DataSource/JdbcTemplate 时切换为 MySQL 版。</p>
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration"
})
@EnableScheduling
@EnableConfigurationProperties(EventDrivenProperties.class)
@ConditionalOnProperty(prefix = "eventdriven", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EventDrivenAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper eventDrivenObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // Instant 必须序列化为 ISO-8601 字符串：默认的时间戳格式在 Jackson 2.17+ 会写成科学计数法，
        // 消费端 Instant.parse 无法解析。
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "eventdriven", name = "persistence",
            havingValue = "memory", matchIfMissing = true)
    public OutboxStore inMemoryOutboxStore() {
        return new InMemoryOutboxStore();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "eventdriven", name = "persistence",
            havingValue = "memory", matchIfMissing = true)
    public ProcessedEventRegistry inMemoryProcessedEventRegistry() {
        return new InMemoryProcessedEventRegistry();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JdbcTemplate.class)
    @ConditionalOnProperty(prefix = "eventdriven", name = "persistence", havingValue = "jdbc")
    static class JdbcPersistenceConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(JdbcTemplate.class)
        public OutboxStore jdbcOutboxStore(JdbcTemplate jdbcTemplate, DataSource dataSource) {
            return new JdbcOutboxStore(jdbcTemplate, dataSource);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(JdbcTemplate.class)
        public ProcessedEventRegistry jdbcProcessedEventRegistry(JdbcTemplate jdbcTemplate) {
            return new JdbcProcessedEventRegistry(jdbcTemplate);
        }
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
        return new OutboxRelay(outboxStore, rabbitTemplate, objectMapper, properties);
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
