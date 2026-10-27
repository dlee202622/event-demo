package com.example.demo.rocketmq.producer;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/**
 * 广播消息生产者，向 {@code demo-broadcast-topic} 发送消息。
 */
@Component
public class BroadcastProducer {

    private final RocketMQTemplate rocketTemplate;

    public BroadcastProducer(RocketMQTemplate rocketTemplate) {
        this.rocketTemplate = rocketTemplate;
    }

    /** 发送广播消息。 */
    public void send(String message) {
        rocketTemplate.convertAndSend("demo-broadcast-topic", message);
    }
}
