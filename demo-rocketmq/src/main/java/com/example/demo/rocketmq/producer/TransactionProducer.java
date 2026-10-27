package com.example.demo.rocketmq.producer;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
public class TransactionProducer {

    private final RocketMQTemplate rocketTemplate;

    public TransactionProducer(RocketMQTemplate rocketTemplate) {
        this.rocketTemplate = rocketTemplate;
    }

    public void send(String message) {
        rocketTemplate.sendMessageInTransaction("demo-tx-topic",
                MessageBuilder.withPayload(message).build(), null);
    }
}
