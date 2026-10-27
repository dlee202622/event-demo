package com.example.demo.rocketmq.producer;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 事务消息生产者，发送半消息后由监听器执行本地事务。
 */
@Component
public class TransactionProducer {

    private final RocketMQTemplate rocketTemplate;

    public TransactionProducer(RocketMQTemplate rocketTemplate) {
        this.rocketTemplate = rocketTemplate;
    }

    /** 发送事务消息。 */
    public void send(String message) {
        rocketTemplate.sendMessageInTransaction("demo-tx-topic",
                MessageBuilder.withPayload(message).build(), null);
    }
}
