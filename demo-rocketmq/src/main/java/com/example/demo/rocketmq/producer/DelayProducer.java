package com.example.demo.rocketmq.producer;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
public class DelayProducer {

    private final RocketMQTemplate rocketTemplate;

    public DelayProducer(RocketMQTemplate rocketTemplate) {
        this.rocketTemplate = rocketTemplate;
    }

    public void send(String message, int delayLevel) {
        rocketTemplate.syncSend("demo-delay-topic",
                MessageBuilder.withPayload(message).build(),
                3000, delayLevel);
    }
}
