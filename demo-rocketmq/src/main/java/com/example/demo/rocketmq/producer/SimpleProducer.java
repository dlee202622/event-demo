package com.example.demo.rocketmq.producer;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/**
 * 普通消息生产者，向 {@code demo-simple-topic} 发送消息。
 */
@Component
public class SimpleProducer {

    private final RocketMQTemplate rocketTemplate;

    public SimpleProducer(RocketMQTemplate rocketTemplate) {
        this.rocketTemplate = rocketTemplate;
    }

    /** 发送普通消息。 */
    public void send(String message) {
        rocketTemplate.convertAndSend("demo-simple-topic", message);
    }
}
