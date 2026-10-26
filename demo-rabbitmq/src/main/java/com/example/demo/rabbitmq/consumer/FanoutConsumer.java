package com.example.demo.rabbitmq.consumer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class FanoutConsumer {

    private static final Logger log = LoggerFactory.getLogger(FanoutConsumer.class);

    @RabbitListener(queues = RabbitMQConfig.FANOUT_QUEUE_A)
    public void receiveA(String message) {
        log.info("[FanoutConsumer-A] received: {}", message);
    }

    @RabbitListener(queues = RabbitMQConfig.FANOUT_QUEUE_B)
    public void receiveB(String message) {
        log.info("[FanoutConsumer-B] received: {}", message);
    }
}
