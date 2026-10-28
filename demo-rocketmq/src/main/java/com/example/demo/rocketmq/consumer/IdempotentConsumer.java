package com.example.demo.rocketmq.consumer;

import com.example.demo.rocketmq.config.ReliabilityTopics;
import com.example.demo.rocketmq.support.InMemoryIdempotentStore;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 幂等消费演示：同一条业务消息无论投递多少次，业务逻辑只执行一次。
 *
 * <p>消息体直接用业务唯一 ID（例如订单号、事件 ID），而不是依赖 Broker 生成的 messageId。
 * 原因是：重投时 Broker 会生成新的 messageId，只有业务 ID 才能跨投递保持不变。</p>
 */
@Component
@RocketMQMessageListener(
        topic = ReliabilityTopics.IDEMPOTENT_TOPIC,
        consumerGroup = ReliabilityTopics.IDEMPOTENT_GROUP
)
public class IdempotentConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumer.class);

    private final InMemoryIdempotentStore idempotentStore;

    public IdempotentConsumer(InMemoryIdempotentStore idempotentStore) {
        this.idempotentStore = idempotentStore;
    }

    /** 先抢占去重记录，再执行真正的业务；重复消息只打日志并跳过。 */
    @Override
    public void onMessage(String messageId) {
        if (!idempotentStore.tryAcquire("rocketmq", messageId)) {
            log.info("[IdempotentConsumer] duplicate ignored: {}", messageId);
            return;
        }
        try {
            // 模拟业务处理。真实场景可能是扣库存、记流水、发积分等。
            log.info("[IdempotentConsumer] processed: {}", messageId);
        } catch (RuntimeException e) {
            // 处理失败必须撤销登记，否则 RocketMQ 重投时会被误判为重复消息而丢弃
            idempotentStore.release("rocketmq", messageId);
            throw e;
        }
    }
}
