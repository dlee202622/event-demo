package com.example.demo.rabbitmq.producer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Direct 路由生产者，根据 routingKey 将消息路由到匹配的队列。
 */
@Component
public class DirectProducer {

    private final RabbitTemplate rabbitTemplate;

    public DirectProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /** 按指定 routingKey 发送消息到 Direct 交换机。 */
    public void send(String routingKey, String message) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.DIRECT_EXCHANGE, routingKey, message);
    }
}
