package com.example.demo.rocketmq.producer;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderlyProducer {

    private final RocketMQTemplate rocketTemplate;

    public OrderlyProducer(RocketMQTemplate rocketTemplate) {
        this.rocketTemplate = rocketTemplate;
    }

    public void send(String orderKey, String message) {
        rocketTemplate.syncSendOrderly("demo-orderly-topic", message, orderKey);
    }
}
