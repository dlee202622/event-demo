package com.example.demo.rabbitmq.producer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 工作队列生产者，向 {@code demo.work.queue} 发送消息。
 */
@Component
public class WorkProducer {

    private final RabbitTemplate rabbitTemplate;

    public WorkProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /** 发送消息到工作队列。 */
    public void send(String message) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.WORK_QUEUE, message);
    }
}
