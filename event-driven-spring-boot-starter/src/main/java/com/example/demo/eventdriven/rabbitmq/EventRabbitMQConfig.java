package com.example.demo.eventdriven.rabbitmq;

import com.example.demo.eventdriven.autoconfigure.EventDrivenProperties;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置：声明事件交换机和队列。
 * 
 * <p>默认使用 Topic 交换机，支持灵活的路由规则。
 * 队列名、交换机名、路由 key 前缀均可通过配置自定义。</p>
 */
@Configuration
@EnableRabbit
public class EventRabbitMQConfig {
    
    public static final String DLQ_QUEUE = "event-driven.dlq";
    
    private final EventDrivenProperties properties;
    
    public EventRabbitMQConfig(EventDrivenProperties properties) {
        this.properties = properties;
    }
    
    @Bean
    public TopicExchange eventExchange() {
        return new TopicExchange(
                properties.getRabbitmq().getExchange());
    }
    
    @Bean
    public Queue eventQueue() {
        return QueueBuilder.durable(
                        properties.getRabbitmq().getQueue())
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", DLQ_QUEUE)
                .build();
    }
    
    @Bean
    public Binding eventBinding() {
        return BindingBuilder.bind(eventQueue())
                .to(eventExchange())
                .with(properties.getRabbitmq().getRoutingKeyPrefix() + ".#");
    }
    
    @Bean
    public Queue dlqQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }
    
    public String getQueueName() {
        return properties.getRabbitmq().getQueue();
    }
}
