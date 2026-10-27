package com.example.demo.rocketmq.consumer;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
        topic = "demo-simple-topic",
        consumerGroup = "demo-simple-consumer-group"
)
public class SimpleConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(SimpleConsumer.class);

    @Override
    public void onMessage(String message) {
        log.info("[SimpleConsumer] received: {}", message);
    }
}
