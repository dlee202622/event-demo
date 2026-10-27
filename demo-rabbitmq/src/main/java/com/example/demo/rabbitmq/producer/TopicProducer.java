package com.example.demo.rabbitmq.producer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Topic 主题生产者，支持通配符路由（如 {@code order.*}、{@code #.created}）。
 */
@Component
public class TopicProducer {

    private final RabbitTemplate rabbitTemplate;

    public TopicProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /** 按指定 routingKey 发送消息到 Topic 交换机。 */
    public void send(String routingKey, String message) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.TOPIC_EXCHANGE, routingKey, message);
    }
}
