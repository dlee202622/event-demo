package com.example.demo.rabbitmq.consumer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import com.example.demo.rabbitmq.support.Delays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 顺序消费的反例：并发消费同一条队列。
 *
 * <p>4 个线程各自拉取消息，处理耗时随机，先取到的不一定先处理完，
 * 日志顺序必然被打乱。把它和 {@link OrderedConsumer} 对比，
 * 就能理解“有序”来自队列与消费者的约束，而不是 Broker 的默认行为。</p>
 */
@Component
public class UnorderedConsumer {

    private static final Logger log = LoggerFactory.getLogger(UnorderedConsumer.class);

    @RabbitListener(queues = RabbitMQConfig.UNORDERED_QUEUE, concurrency = "4")
    public void receive(String message) {
        // 随机耗时放大乱序效果；真实业务中数据库、下游 RPC 的耗时差异就是这种效果
        Delays.sleep(ThreadLocalRandom.current().nextInt(10, 80));
        log.info("[UnorderedConsumer] thread={} received: {}", Thread.currentThread().getName(), message);
    }
}
