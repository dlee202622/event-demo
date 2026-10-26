package com.example.demo.rabbitmq.consumer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class DeadLetterConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterConsumer.class);

    @RabbitListener(queues = RabbitMQConfig.DLQ_MAIN_QUEUE)
    public void receiveMain(String message) {
        log.info("[DeadLetterConsumer-main] received: {}, will reject to DLQ", message);
        throw new AmqpRejectAndDontRequeueException("reject to DLQ demo");
    }

    @RabbitListener(queues = RabbitMQConfig.DLX_QUEUE)
    public void receiveDlx(String message) {
        log.info("[DeadLetterConsumer-DLQ] received dead letter: {}", message);
    }
}
