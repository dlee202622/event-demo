package com.example.demo.rabbitmq.consumer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class WorkConsumer {

    private static final Logger log = LoggerFactory.getLogger(WorkConsumer.class);

    @RabbitListener(queues = RabbitMQConfig.WORK_QUEUE, concurrency = "2")
    public void receive(String message) {
        log.info("[WorkConsumer] thread={} received: {}", Thread.currentThread().getName(), message);
    }
}
