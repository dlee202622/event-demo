package com.example.demo.rocketmq.consumer;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
        topic = "demo-tx-topic",
        consumerGroup = "demo-tx-consumer-group"
)
public class TransactionConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(TransactionConsumer.class);

    @Override
    public void onMessage(String message) {
        log.info("[TransactionConsumer] received transactional message: {}", message);
    }
}
