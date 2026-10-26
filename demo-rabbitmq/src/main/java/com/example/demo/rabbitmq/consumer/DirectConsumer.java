package com.example.demo.rabbitmq.consumer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class DirectConsumer {

    private static final Logger log = LoggerFactory.getLogger(DirectConsumer.class);

    @RabbitListener(queues = RabbitMQConfig.DIRECT_QUEUE_ORDER)
    public void receiveOrder(String message) {
        log.info("[DirectConsumer-order] received: {}", message);
    }

    @RabbitListener(queues = RabbitMQConfig.DIRECT_QUEUE_PAYMENT)
    public void receivePayment(String message) {
        log.info("[DirectConsumer-payment] received: {}", message);
    }
}
