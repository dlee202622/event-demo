package com.example.demo.rocketmq.consumer;

import com.example.demo.rocketmq.config.ReliabilityTopics;
import com.example.demo.rocketmq.support.Delays;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 高优先级消费者。
 *
 * <p>RocketMQ 没有消息优先级，这里用「独立 Topic + 独立消费者组」做资源隔离：
 * 低优先级消息堆积时，高优先级消费者仍有自己的拉取线程和队列，不会被拖慢。</p>
 *
 * <p>代价是优先级不是由 Broker 自动判断的：生产者必须把消息发到对应优先级的 Topic，
 * 也无法做到「后到的高优先级消息插队到已进入低优先级 Topic 的消息之前」。</p>
 */
@Component
@RocketMQMessageListener(
        topic = ReliabilityTopics.PRIORITY_HIGH_TOPIC,
        consumerGroup = ReliabilityTopics.PRIORITY_HIGH_GROUP
)
public class PriorityHighConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(PriorityHighConsumer.class);

    @Override
    public void onMessage(String message) {
        // 高优先级处理最快，突出“低优先级积压不影响高优先级”
        Delays.sleep(5);
        log.info("[PriorityHighConsumer] received: {}", message);
    }
}
