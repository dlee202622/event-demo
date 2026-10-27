package com.example.demo.rocketmq.consumer;

import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 广播消息消费者，每个消费者实例都能收到全量消息。
 */
@Component
@RocketMQMessageListener(
        topic = "demo-broadcast-topic",
        consumerGroup = "demo-broadcast-consumer-group",
        messageModel = MessageModel.BROADCASTING
)
public class BroadcastConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(BroadcastConsumer.class);

    /** 接收广播消息。 */
    @Override
    public void onMessage(String message) {
        log.info("[BroadcastConsumer] received: {}", message);
    }
}
