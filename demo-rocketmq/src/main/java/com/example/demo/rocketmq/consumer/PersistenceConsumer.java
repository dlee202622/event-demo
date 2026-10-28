package com.example.demo.rocketmq.consumer;

import com.example.demo.rocketmq.config.ReliabilityTopics;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 持久化演示的消费者。
 *
 * <p>RocketMQ 的消息默认写入 CommitLog 并落盘，不需要像 RabbitMQ 那样显式设置持久化标志。
 * 先暂停该消费者，再发送消息并重启 Broker，恢复后仍能收到消息，即说明消息已经持久化。</p>
 */
@Component
@RocketMQMessageListener(
        topic = ReliabilityTopics.PERSISTENCE_TOPIC,
        consumerGroup = ReliabilityTopics.PERSISTENCE_GROUP
)
public class PersistenceConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(PersistenceConsumer.class);

    @Override
    public void onMessage(String message) {
        log.info("[PersistenceConsumer] received: {}", message);
    }
}
