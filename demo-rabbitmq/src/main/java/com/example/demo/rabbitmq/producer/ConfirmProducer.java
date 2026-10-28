package com.example.demo.rabbitmq.producer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 带 Publisher Confirm 的生产者，支持消息确认和路由失败回调。
 */
@Component
public class ConfirmProducer {

    private static final Logger log = LoggerFactory.getLogger(ConfirmProducer.class);

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final RabbitTemplate rabbitTemplate;

    public ConfirmProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /** 初始化 Publisher Confirm 和 Return 回调。 */
    @PostConstruct
    public void init() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            String id = correlationData == null ? "n/a" : correlationData.getId();
            if (ack) {
                log.info("[ConfirmProducer] message confirmed by broker, id={}", id);
            } else {
                log.warn("[ConfirmProducer] message NOT confirmed, id={}, cause: {}", id, cause);
            }
        });
        rabbitTemplate.setReturnsCallback(returned -> {
            log.warn("[ConfirmProducer] message returned: exchange={}, routingKey={}, replyCode={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode());
        });
    }

    /** 发送消息到简单队列，并携带关联数据以接收 Publisher Confirm。 */
    public void send(String message) {
        CorrelationData correlationData = new CorrelationData("confirm-" + SEQUENCE.incrementAndGet());
        // 空字符串是默认交换机，routing key 直接写队列名
        rabbitTemplate.convertAndSend("", RabbitMQConfig.SIMPLE_QUEUE, message, correlationData);
    }

    /** 发送消息到指定队列。 */
    public void sendToQueue(String queue, String message) {
        rabbitTemplate.convertAndSend(queue, message);
    }
}
