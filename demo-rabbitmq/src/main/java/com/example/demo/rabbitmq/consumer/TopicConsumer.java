package com.example.demo.rabbitmq.consumer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Topic 主题消费者，分别监听全量订单和创建事件队列。
 */
@Component
public class TopicConsumer {

    private static final Logger log = LoggerFactory.getLogger(TopicConsumer.class);

    /** 接收匹配 {@code order.*} 的全部订单消息。 */
    @RabbitListener(queues = RabbitMQConfig.TOPIC_QUEUE_ALL_ORDER)
    public void receiveAllOrder(String message) {
        log.info("[TopicConsumer-all-order] received: {}", message);
    }

    /** 接收匹配 {@code #.created} 的创建事件消息。 */
    @RabbitListener(queues = RabbitMQConfig.TOPIC_QUEUE_CREATED)
    public void receiveCreated(String message) {
        log.info("[TopicConsumer-created] received: {}", message);
    }
}
