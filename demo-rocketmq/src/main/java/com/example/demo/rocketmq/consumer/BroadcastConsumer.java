package com.example.demo.rocketmq.consumer;

import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
        topic = "demo-broadcast-topic",
        consumerGroup = "demo-broadcast-consumer-group",
        messageModel = MessageModel.BROADCASTING
)
public class BroadcastConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(BroadcastConsumer.class);

    @Override
    public void onMessage(String message) {
        log.info("[BroadcastConsumer] received: {}", message);
    }
}
