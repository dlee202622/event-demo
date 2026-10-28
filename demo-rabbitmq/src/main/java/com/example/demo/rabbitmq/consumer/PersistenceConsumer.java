package com.example.demo.rabbitmq.consumer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 持久化演示的消费者。
 *
 * <p>它同样支持暂停/恢复：先暂停，再发送消息并重启 Broker，
 * 重启后恢复消费，就能看到持久化消息仍然被投递过来。</p>
 */
@Component
public class PersistenceConsumer {

    private static final Logger log = LoggerFactory.getLogger(PersistenceConsumer.class);

    @RabbitListener(id = "persistenceConsumer", queues = RabbitMQConfig.PERSISTENCE_QUEUE)
    public void receive(String message) {
        log.info("[PersistenceConsumer] received: {}", message);
    }
}
