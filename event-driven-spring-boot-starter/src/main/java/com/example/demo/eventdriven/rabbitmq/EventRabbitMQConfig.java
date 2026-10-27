package com.example.demo.eventdriven.rabbitmq;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置：声明事件交换机和队列。
 * 
 * <p>默认使用 Topic 交换机，支持灵活的路由规则。</p>
 */
@Configuration
@EnableRabbit
public class EventRabbitMQConfig {
    
    /** 事件交换机名称 */
    public static final String EXCHANGE = "event-driven.exchange";
    
    /** 事件队列名称 */
    public static final String QUEUE = "event-driven.queue";
    
    /** 路由 key 前缀 */
    public static final String ROUTING_KEY_PREFIX = "event";
    
    /** 死信交换机 */
    public static final String DLX_EXCHANGE = "event-driven.dlx";
    
    /** 死信队列 */
    public static final String DLQ_QUEUE = "event-driven.dlq";
    
    @Bean
    public TopicExchange eventExchange() {
        return new TopicExchange(EXCHANGE);
    }
    
    @Bean
    public Queue eventQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", DLQ_QUEUE)
                .build();
    }
    
    @Bean
    public Binding eventBinding() {
        return BindingBuilder.bind(eventQueue())
                .to(eventExchange())
                .with(ROUTING_KEY_PREFIX + ".#");
    }
    
    @Bean
    public Queue dlqQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }
}
