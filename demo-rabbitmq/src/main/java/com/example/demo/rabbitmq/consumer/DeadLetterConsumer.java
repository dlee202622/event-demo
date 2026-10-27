package com.example.demo.rabbitmq.consumer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 死信队列消费者，演示消息被拒绝后路由到死信队列的流程。
 */
@Component
public class DeadLetterConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterConsumer.class);

    /** 接收主队列消息并抛出异常将其拒绝到死信队列。 */
    @RabbitListener(queues = RabbitMQConfig.DLQ_MAIN_QUEUE)
    public void receiveMain(String message) {
        log.info("[DeadLetterConsumer-main] received: {}, will reject to DLQ", message);
        throw new AmqpRejectAndDontRequeueException("reject to DLQ demo");
    }

    /** 接收死信队列中的消息。 */
    @RabbitListener(queues = RabbitMQConfig.DLX_QUEUE)
    public void receiveDlx(String message) {
        log.info("[DeadLetterConsumer-DLQ] received dead letter: {}", message);
    }
}
