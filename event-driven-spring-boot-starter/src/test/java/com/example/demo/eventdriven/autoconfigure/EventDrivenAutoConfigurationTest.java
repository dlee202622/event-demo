package com.example.demo.eventdriven.autoconfigure;

import com.example.demo.eventdriven.domain.DomainEventPublisher;
import com.example.demo.eventdriven.idempotency.ProcessedEventRegistry;
import com.example.demo.eventdriven.outbox.OutboxRelay;
import com.example.demo.eventdriven.outbox.OutboxStore;
import com.example.demo.eventdriven.rabbitmq.EventMessageListener;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class EventDrivenAutoConfigurationTest {
    
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EventDrivenAutoConfiguration.class));
    
    @Test
    void shouldCreateDefaultBeans() {
        contextRunner
                .withUserConfiguration(RabbitTemplateConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxStore.class);
                    assertThat(context).hasSingleBean(ProcessedEventRegistry.class);
                    assertThat(context).hasSingleBean(DomainEventPublisher.class);
                    assertThat(context).hasSingleBean(OutboxRelay.class);
                    assertThat(context).hasSingleBean(EventMessageListener.class);
                });
    }
    
    @Test
    void shouldNotCreateBeansWhenDisabled() {
        contextRunner
                .withPropertyValues("eventdriven.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(OutboxStore.class);
                    assertThat(context).doesNotHaveBean(OutboxRelay.class);
                });
    }
    
    @Test
    void shouldAllowCustomOutboxStore() {
        contextRunner
                .withUserConfiguration(CustomOutboxStoreConfig.class, RabbitTemplateConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxStore.class);
                    assertThat(context.getBean(OutboxStore.class))
                            .isInstanceOf(CustomOutboxStore.class);
                });
    }
    
    @Test
    void shouldUseJdbcStoresWhenPersistenceIsJdbc() {
        contextRunner
                .withUserConfiguration(RabbitTemplateConfig.class, JdbcConfig.class)
                .withPropertyValues("eventdriven.persistence=jdbc")
                .run(context -> {
                    assertThat(context.getBean(OutboxStore.class))
                            .isInstanceOf(com.example.demo.eventdriven.outbox.JdbcOutboxStore.class);
                    assertThat(context.getBean(ProcessedEventRegistry.class))
                            .isInstanceOf(com.example.demo.eventdriven.idempotency.JdbcProcessedEventRegistry.class);
                });
    }

    @Configuration
    static class JdbcConfig {
        @Bean
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate(javax.sql.DataSource dataSource) {
            return new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        }

        @Bean
        javax.sql.DataSource dataSource() {
            return org.mockito.Mockito.mock(javax.sql.DataSource.class);
        }
    }

    @Configuration
    static class RabbitTemplateConfig {
        @Bean
        org.springframework.amqp.rabbit.core.RabbitTemplate rabbitTemplate() {
            return org.mockito.Mockito.mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
        }
    }
    
    @Configuration
    static class CustomOutboxStoreConfig {
        @Bean
        OutboxStore customOutboxStore() {
            return new CustomOutboxStore();
        }
    }
    
    static class CustomOutboxStore implements OutboxStore {
        @Override
        public void append(com.example.demo.eventdriven.outbox.OutboxEvent event) {}
        
        @Override
        public java.util.List<com.example.demo.eventdriven.outbox.OutboxEvent> claimPending(int limit) {
            return java.util.List.of();
        }

        @Override
        public void markDelivered(String outboxId) {}

        @Override
        public void markFailed(String outboxId) {}
        
        @Override
        public java.util.List<com.example.demo.eventdriven.outbox.OutboxEvent> findAll() {
            return java.util.List.of();
        }
        
        @Override
        public int pendingCount() { return 0; }
        
        @Override
        public int size() { return 0; }
        
        @Override
        public void clear() {}
    }
}
