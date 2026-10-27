package com.example.demo.rocketmq.producer;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
public class TagProducer {

    private final RocketMQTemplate rocketTemplate;

    public TagProducer(RocketMQTemplate rocketTemplate) {
        this.rocketTemplate = rocketTemplate;
    }

    public void send(String tag, String message) {
        rocketTemplate.send("demo-tag-topic:" + tag, MessageBuilder.withPayload(message).build());
    }
}
