package com.example.demo.rabbitmq.consumer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import com.example.demo.rabbitmq.support.Delays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 优先级消费演示。
 *
 * <p>队列声明了 {@code x-max-priority}（见 {@link RabbitMQConfig#priorityQueue()}），
 * Broker 会把 Ready 状态的消息按 priority 从高到低投递。</p>
 *
 * <p>几个容易误解的点：</p>
 * <ul>
 *   <li>优先级只对**还在队列里排队**的消息生效。消费者空闲时，低优先级消息一进队列就被取走，
 *       之后才到的高优先级消息无法“抢回来”；</li>
 *   <li>消费者用 {@code concurrency = 1}，避免多个线程同时取走消息掩盖排序效果；</li>
 *   <li>prefetch 越大，消费者一次拿走的低优先级消息越多，高优先级消息能插队的空间越小。
 *       真实业务里要权衡吞吐和优先级灵敏度。</li>
 * </ul>
 */
@Component
public class PriorityConsumer {

    private static final Logger log = LoggerFactory.getLogger(PriorityConsumer.class);

    /** id 用于通过 RabbitListenerEndpointRegistry 暂停/恢复该消费者。 */
    @RabbitListener(id = "priorityConsumer", queues = RabbitMQConfig.PRIORITY_QUEUE, concurrency = "1")
    public void receive(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        Integer priority = message.getMessageProperties().getPriority();
        // 放大单条处理耗时，让“同一批消息按优先级出队”的现象更直观
        Delays.sleep(30);
        log.info("[PriorityConsumer] received: {} (priority={})", body, priority);
    }
}
