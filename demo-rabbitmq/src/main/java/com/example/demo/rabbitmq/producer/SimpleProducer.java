package com.example.demo.rabbitmq.producer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class SimpleProducer {

    private final RabbitTemplate rabbitTemplate;

    public SimpleProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void send(String message) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.SIMPLE_QUEUE, message);
    }
}
