package com.example.demo.rocketmq.producer;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 延迟消息生产者，支持指定延迟级别延迟投递。
 */
@Component
public class DelayProducer {

    private final RocketMQTemplate rocketTemplate;

    public DelayProducer(RocketMQTemplate rocketTemplate) {
        this.rocketTemplate = rocketTemplate;
    }

    /** 发送延迟消息，delayLevel 对应 RocketMQ 延迟等级。 */
    public void send(String message, int delayLevel) {
        rocketTemplate.syncSend("demo-delay-topic",
                MessageBuilder.withPayload(message).build(),
                3000, delayLevel);
    }
}
