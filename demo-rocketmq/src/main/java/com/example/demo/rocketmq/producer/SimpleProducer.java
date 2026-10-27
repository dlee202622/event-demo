package com.example.demo.rocketmq.producer;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

@Component
public class SimpleProducer {

    private final RocketMQTemplate rocketTemplate;

    public SimpleProducer(RocketMQTemplate rocketTemplate) {
        this.rocketTemplate = rocketTemplate;
    }

    public void send(String message) {
        rocketTemplate.convertAndSend("demo-simple-topic", message);
    }
}
