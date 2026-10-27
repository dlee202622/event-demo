package com.example.demo.rabbitmq.consumer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 工作队列消费者，多实例竞争消费 {@code demo.work.queue}。
 */
@Component
public class WorkConsumer {

    private static final Logger log = LoggerFactory.getLogger(WorkConsumer.class);

    /** 接收消息，并发度为 2。 */
    @RabbitListener(queues = RabbitMQConfig.WORK_QUEUE, concurrency = "2")
    public void receive(String message) {
        log.info("[WorkConsumer] thread={} received: {}", Thread.currentThread().getName(), message);
    }
}
