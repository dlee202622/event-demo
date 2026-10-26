package com.example.demo.rabbitmq.producer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class ConfirmProducer {

    private static final Logger log = LoggerFactory.getLogger(ConfirmProducer.class);

    private final RabbitTemplate rabbitTemplate;

    public ConfirmProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @PostConstruct
    public void init() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (ack) {
                log.info("[ConfirmProducer] message confirmed by broker");
            } else {
                log.warn("[ConfirmProducer] message NOT confirmed, cause: {}", cause);
            }
        });
        rabbitTemplate.setReturnsCallback(returned -> {
            log.warn("[ConfirmProducer] message returned: exchange={}, routingKey={}, replyCode={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode());
        });
    }

    public void send(String message) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.SIMPLE_QUEUE, message);
    }

    public void sendToQueue(String queue, String message) {
        rabbitTemplate.convertAndSend(queue, message);
    }
}
