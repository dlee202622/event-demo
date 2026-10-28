package com.example.demo.rabbitmq.consumer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import com.example.demo.rabbitmq.support.Delays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 顺序消费的正面示例。
 *
 * <p>RabbitMQ 只保证“单个队列 + 单个消费者”下的投递顺序：一个消费者一次只处理一条消息，
 * 处理完才取下一条。因此这里设置 {@code concurrency = "1"}，并用固定的 20ms 处理耗时
 * 放大观察窗口。生产环境若要顺序，还需保证：</p>
 * <ul>
 *   <li>同一业务 key 的消息只进这一个队列（不要用 competing consumers）；</li>
 *   <li>消费失败不要跳过，否则后续消息会越过失败的那条；</li>
 *   <li>需要并行时按业务 key 拆成多个队列，而不是给同一队列加消费者。</li>
 * </ul>
 */
@Component
public class OrderedConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderedConsumer.class);

    /** id 用于通过 RabbitListenerEndpointRegistry 暂停/恢复该消费者。 */
    @RabbitListener(id = "orderedConsumer", queues = RabbitMQConfig.ORDERED_QUEUE, concurrency = "1")
    public void receive(String message) {
        Delays.sleep(20);
        log.info("[OrderedConsumer] received: {}", message);
    }
}
