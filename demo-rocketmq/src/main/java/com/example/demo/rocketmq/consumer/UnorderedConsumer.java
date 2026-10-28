package com.example.demo.rocketmq.consumer;

import com.example.demo.rocketmq.config.ReliabilityTopics;
import com.example.demo.rocketmq.support.Delays;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 顺序消费的反例：默认的并发消费（CONCURRENTLY）。
 *
 * <p>多个消费线程同时处理同一 Topic 的消息，处理耗时随机，
 * 先取到的不一定先打印，日志顺序会被打乱。它与 {@code OrderlyConsumer}
 * 使用相同的发送方式（普通发送），差别只在消费模式。</p>
 */
@Component
@RocketMQMessageListener(
        topic = ReliabilityTopics.UNORDERED_TOPIC,
        consumerGroup = ReliabilityTopics.UNORDERED_GROUP
)
public class UnorderedConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(UnorderedConsumer.class);

    @Override
    public void onMessage(String message) {
        // 随机耗时放大乱序效果；真实业务中数据库、下游 RPC 的耗时差异就是这种效果
        Delays.sleep(ThreadLocalRandom.current().nextInt(10, 80));
        log.info("[UnorderedConsumer] thread={} received: {}", Thread.currentThread().getName(), message);
    }
}
