package com.example.demo.rocketmq.consumer;

import com.example.demo.rocketmq.config.ReliabilityTopics;
import com.example.demo.rocketmq.support.Delays;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 普通优先级消费者，处理速度介于高、低之间。
 */
@Component
@RocketMQMessageListener(
        topic = ReliabilityTopics.PRIORITY_NORMAL_TOPIC,
        consumerGroup = ReliabilityTopics.PRIORITY_NORMAL_GROUP
)
public class PriorityNormalConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(PriorityNormalConsumer.class);

    @Override
    public void onMessage(String message) {
        Delays.sleep(30);
        log.info("[PriorityNormalConsumer] received: {}", message);
    }
}
