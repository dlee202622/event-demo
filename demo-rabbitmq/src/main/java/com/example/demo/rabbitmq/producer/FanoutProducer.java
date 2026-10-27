package com.example.demo.rabbitmq.producer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Fanout 广播生产者，向 Fanout 交换机发送消息，所有绑定队列均会收到。
 */
@Component
public class FanoutProducer {

    private final RabbitTemplate rabbitTemplate;

    public FanoutProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /** 发送广播消息到 Fanout 交换机。 */
    public void send(String message) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.FANOUT_EXCHANGE, "", message);
    }
}
