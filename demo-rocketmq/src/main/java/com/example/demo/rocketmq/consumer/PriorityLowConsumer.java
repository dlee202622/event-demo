package com.example.demo.rocketmq.consumer;

import com.example.demo.rocketmq.config.ReliabilityTopics;
import com.example.demo.rocketmq.support.Delays;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 低优先级消费者，故意处理得最慢。
 *
 * <p>配合 /api/rocketmq/priority/pause?level=low，可以先把低优先级消费者停掉制造积压，
 * 再发高优先级消息，观察高优先级仍然被及时消费——这就是“优先级隔离”的效果。</p>
 */
@Component
@RocketMQMessageListener(
        topic = ReliabilityTopics.PRIORITY_LOW_TOPIC,
        consumerGroup = ReliabilityTopics.PRIORITY_LOW_GROUP
)
public class PriorityLowConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(PriorityLowConsumer.class);

    @Override
    public void onMessage(String message) {
        Delays.sleep(100);
        log.info("[PriorityLowConsumer] received: {}", message);
    }
}
