package com.example.demo.rabbitmq.consumer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import com.example.demo.rabbitmq.support.Delays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 积压演示的消费者。
 *
 * <p>故意让每条消息慢 5ms，配合“暂停消费者 + 批量发送”的接口，
 * 可以在管理台看到 Ready 持续增长；恢复后观察它逐步被消费。</p>
 */
@Component
public class BacklogConsumer {

    private static final Logger log = LoggerFactory.getLogger(BacklogConsumer.class);

    /** 固定 id，ReliabilityController 通过它暂停和恢复消费。 */
    @RabbitListener(id = "backlogConsumer", queues = RabbitMQConfig.BACKLOG_QUEUE)
    public void receive(String message) {
        Delays.sleep(5);
        log.info("[BacklogConsumer] consumed: {}", message);
    }
}
