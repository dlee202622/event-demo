package com.example.demo.rocketmq.consumer;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
        topic = "demo-tag-topic",
        consumerGroup = "demo-tag-consumer-group",
        selectorExpression = "TagA"
)
public class TagFilterConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(TagFilterConsumer.class);

    @Override
    public void onMessage(String message) {
        log.info("[TagFilterConsumer] received (TagA only): {}", message);
    }
}
