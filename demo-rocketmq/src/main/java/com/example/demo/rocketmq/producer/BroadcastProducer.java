package com.example.demo.rocketmq.producer;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

@Component
public class BroadcastProducer {

    private final RocketMQTemplate rocketTemplate;

    public BroadcastProducer(RocketMQTemplate rocketTemplate) {
        this.rocketTemplate = rocketTemplate;
    }

    public void send(String message) {
        rocketTemplate.convertAndSend("demo-broadcast-topic", message);
    }
}
