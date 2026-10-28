package com.example.demo.rocketmq.consumer;

import com.example.demo.rocketmq.config.ReliabilityTopics;
import com.example.demo.rocketmq.support.Delays;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 积压演示的消费者。
 *
 * <p>故意让每条消息慢 5ms，配合“暂停消费者 + 批量发送”的接口，
 * 可以在 Dashboard 的消费者页看到 Diff 持续增长；恢复后观察它逐步回落。</p>
 */
@Component
@RocketMQMessageListener(
        topic = ReliabilityTopics.BACKLOG_TOPIC,
        consumerGroup = ReliabilityTopics.BACKLOG_GROUP
)
public class BacklogConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(BacklogConsumer.class);

    @Override
    public void onMessage(String message) {
        Delays.sleep(5);
        log.info("[BacklogConsumer] consumed: {}", message);
    }
}
