package com.example.demo.rocketmq.producer;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 带标签消息生产者，通过 tag 实现消息分类过滤。
 */
@Component
public class TagProducer {

    private final RocketMQTemplate rocketTemplate;

    public TagProducer(RocketMQTemplate rocketTemplate) {
        this.rocketTemplate = rocketTemplate;
    }

    /** 发送带指定 tag 的消息。 */
    public void send(String tag, String message) {
        rocketTemplate.send("demo-tag-topic:" + tag, MessageBuilder.withPayload(message).build());
    }
}
