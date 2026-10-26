package com.example.demo.rabbitmq.consumer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class TopicConsumer {

    private static final Logger log = LoggerFactory.getLogger(TopicConsumer.class);

    @RabbitListener(queues = RabbitMQConfig.TOPIC_QUEUE_ALL_ORDER)
    public void receiveAllOrder(String message) {
        log.info("[TopicConsumer-all-order] received: {}", message);
    }

    @RabbitListener(queues = RabbitMQConfig.TOPIC_QUEUE_CREATED)
    public void receiveCreated(String message) {
        log.info("[TopicConsumer-created] received: {}", message);
    }
}
