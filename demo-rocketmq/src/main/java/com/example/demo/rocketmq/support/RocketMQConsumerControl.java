package com.example.demo.rocketmq.support;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.spring.support.DefaultRocketMQListenerContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RocketMQ 消费者暂停/恢复控制。
 *
 * <p>start / stop 会销毁并重建消费线程，代价较大；积压演示只需要“停止拉取”，
 * 所以直接调用客户端 {@link DefaultMQPushConsumer#suspend()} / {@link DefaultMQPushConsumer#resume()}。
 * 暂停期间消费位点保持不变，恢复后从原位点继续消费。</p>
 *
 * <p>容器按 consumerGroup 查找：Spring 容器里每个 {@code @RocketMQMessageListener}
 * 都会注册一个 {@link DefaultRocketMQListenerContainer} Bean，但 Bean 名称是自动生成的序号，
 * 只有 consumerGroup 是稳定的业务标识。这里在首次调用时才查询 Bean，
 * 避免应用启动阶段提前初始化监听容器。</p>
 */
@Component
public class RocketMQConsumerControl {

    private static final Logger log = LoggerFactory.getLogger(RocketMQConsumerControl.class);

    private final ApplicationContext applicationContext;

    /** 记录由本控制器暂停的消费者组，用于 /status 展示。 */
    private final Set<String> suspendedGroups = ConcurrentHashMap.newKeySet();

    public RocketMQConsumerControl(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /** 暂停指定消费者组：客户端停止拉取消息，位点不推进。 */
    public void suspend(String consumerGroup) {
        findContainer(consumerGroup).getConsumer().suspend();
        suspendedGroups.add(consumerGroup);
        log.info("[ConsumerControl] suspended consumer group: {}", consumerGroup);
    }

    /** 恢复指定消费者组：从暂停时的位点继续拉取。 */
    public void resume(String consumerGroup) {
        findContainer(consumerGroup).getConsumer().resume();
        suspendedGroups.remove(consumerGroup);
        log.info("[ConsumerControl] resumed consumer group: {}", consumerGroup);
    }

    public boolean isSuspended(String consumerGroup) {
        return suspendedGroups.contains(consumerGroup);
    }

    private DefaultRocketMQListenerContainer findContainer(String consumerGroup) {
        Map<String, DefaultRocketMQListenerContainer> containers =
                applicationContext.getBeansOfType(DefaultRocketMQListenerContainer.class);
        return containers.values().stream()
                .filter(container -> consumerGroup.equals(container.getConsumerGroup()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "listener container not found for consumer group: " + consumerGroup));
    }
}
