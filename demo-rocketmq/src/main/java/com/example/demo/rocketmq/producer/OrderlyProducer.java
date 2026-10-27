package com.example.demo.rocketmq.producer;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

/**
 * 顺序消息生产者，按 orderKey 哈希保证同一 key 的消息有序。
 */
@Component
public class OrderlyProducer {

    private final RocketMQTemplate rocketTemplate;

    public OrderlyProducer(RocketMQTemplate rocketTemplate) {
        this.rocketTemplate = rocketTemplate;
    }

    /** 按 orderKey 顺序发送消息。 */
    public void send(String orderKey, String message) {
        rocketTemplate.syncSendOrderly("demo-orderly-topic", message, orderKey);
    }
}
