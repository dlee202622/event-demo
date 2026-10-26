package com.example.demo.rabbitmq.consumer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class SimpleConsumer {

    private static final Logger log = LoggerFactory.getLogger(SimpleConsumer.class);

    @RabbitListener(queues = RabbitMQConfig.SIMPLE_QUEUE)
    public void receive(String message) {
        log.info("[SimpleConsumer] received: {}", message);
    }
}
