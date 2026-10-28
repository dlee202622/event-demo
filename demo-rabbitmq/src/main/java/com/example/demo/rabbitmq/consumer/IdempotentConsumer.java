package com.example.demo.rabbitmq.consumer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import com.example.demo.rabbitmq.support.InMemoryIdempotentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 幂等消费演示：同一条业务消息无论投递多少次，业务逻辑只执行一次。
 *
 * <p>消息体直接用业务唯一 ID（例如订单号、事件 ID），而不是依赖 Broker 生成的 messageId。
 * 原因是：重投时 Broker 会生成新的 messageId，只有业务 ID 才能跨投递保持不变。</p>
 */
@Component
public class IdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumer.class);

    private final InMemoryIdempotentStore idempotentStore;

    public IdempotentConsumer(InMemoryIdempotentStore idempotentStore) {
        this.idempotentStore = idempotentStore;
    }

    /** 先抢占去重记录，再执行真正的业务；重复消息只打日志并跳过。 */
    @RabbitListener(queues = RabbitMQConfig.IDEMPOTENT_QUEUE)
    public void receive(String messageId) {
        if (!idempotentStore.tryAcquire("rabbitmq", messageId)) {
            log.info("[IdempotentConsumer] duplicate ignored: {}", messageId);
            return;
        }
        try {
            // 模拟业务处理。真实场景可能是扣库存、记流水、发积分等。
            log.info("[IdempotentConsumer] processed: {}", messageId);
        } catch (RuntimeException e) {
            // 处理失败必须撤销登记，否则 Broker 重投时会被误判为重复消息而丢弃
            idempotentStore.release("rabbitmq", messageId);
            throw e;
        }
    }
}
