package com.example.demo.rocketmq.consumer;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 延迟消息消费者，接收延迟投递的消息。
 */
@Component
@RocketMQMessageListener(
        topic = "demo-delay-topic",
        consumerGroup = "demo-delay-consumer-group"
)
public class DelayConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(DelayConsumer.class);

    /** 接收延迟消息并打印接收时间戳。 */
    @Override
    public void onMessage(String message) {
        log.info("[DelayConsumer] received at {}: {}", System.currentTimeMillis(), message);
    }
}
