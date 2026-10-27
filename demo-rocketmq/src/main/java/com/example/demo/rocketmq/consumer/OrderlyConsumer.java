package com.example.demo.rocketmq.consumer;

import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
        topic = "demo-orderly-topic",
        consumerGroup = "demo-orderly-consumer-group",
        consumeMode = ConsumeMode.ORDERLY
)
public class OrderlyConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(OrderlyConsumer.class);

    @Override
    public void onMessage(String message) {
        log.info("[OrderlyConsumer] thread={} received: {}", Thread.currentThread().getName(), message);
    }
}
