package com.example.demo.rabbitmq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置类，声明演示所需的所有队列、交换机及绑定关系。
 */
@Configuration
public class RabbitMQConfig {

    public static final String SIMPLE_QUEUE = "demo.simple.queue";
    public static final String WORK_QUEUE = "demo.work.queue";

    public static final String FANOUT_EXCHANGE = "demo.fanout.exchange";
    public static final String FANOUT_QUEUE_A = "demo.fanout.queue.a";
    public static final String FANOUT_QUEUE_B = "demo.fanout.queue.b";

    public static final String DIRECT_EXCHANGE = "demo.direct.exchange";
    public static final String DIRECT_QUEUE_ORDER = "demo.direct.queue.order";
    public static final String DIRECT_QUEUE_PAYMENT = "demo.direct.queue.payment";
    public static final String DIRECT_KEY_ORDER = "order.created";
    public static final String DIRECT_KEY_PAYMENT = "order.paid";

    public static final String TOPIC_EXCHANGE = "demo.topic.exchange";
    public static final String TOPIC_QUEUE_ALL_ORDER = "demo.topic.queue.all-order";
    public static final String TOPIC_QUEUE_CREATED = "demo.topic.queue.created";

    public static final String DLX_EXCHANGE = "demo.dlx.exchange";
    public static final String DLX_QUEUE = "demo.dlx.queue";
    public static final String DLQ_MAIN_QUEUE = "demo.dlq.main.queue";

    @Bean
    public Queue simpleQueue() {
        return new Queue(SIMPLE_QUEUE);
    }

    @Bean
    public Queue workQueue() {
        return new Queue(WORK_QUEUE);
    }

    @Bean
    public FanoutExchange fanoutExchange() {
        return new FanoutExchange(FANOUT_EXCHANGE);
    }

    @Bean
    public Queue fanoutQueueA() {
        return new Queue(FANOUT_QUEUE_A);
    }

    @Bean
    public Queue fanoutQueueB() {
        return new Queue(FANOUT_QUEUE_B);
    }

    @Bean
    public Binding fanoutBindingA() {
        return BindingBuilder.bind(fanoutQueueA()).to(fanoutExchange());
    }

    @Bean
    public Binding fanoutBindingB() {
        return BindingBuilder.bind(fanoutQueueB()).to(fanoutExchange());
    }

    @Bean
    public DirectExchange directExchange() {
        return new DirectExchange(DIRECT_EXCHANGE);
    }

    @Bean
    public Queue directQueueOrder() {
        return new Queue(DIRECT_QUEUE_ORDER);
    }

    @Bean
    public Queue directQueuePayment() {
        return new Queue(DIRECT_QUEUE_PAYMENT);
    }

    @Bean
    public Binding directBindingOrder() {
        return BindingBuilder.bind(directQueueOrder()).to(directExchange()).with(DIRECT_KEY_ORDER);
    }

    @Bean
    public Binding directBindingPayment() {
        return BindingBuilder.bind(directQueuePayment()).to(directExchange()).with(DIRECT_KEY_PAYMENT);
    }

    @Bean
    public TopicExchange topicExchange() {
        return new TopicExchange(TOPIC_EXCHANGE);
    }

    @Bean
    public Queue topicQueueAllOrder() {
        return new Queue(TOPIC_QUEUE_ALL_ORDER);
    }

    @Bean
    public Queue topicQueueCreated() {
        return new Queue(TOPIC_QUEUE_CREATED);
    }

    @Bean
    public Binding topicBindingAllOrder() {
        return BindingBuilder.bind(topicQueueAllOrder()).to(topicExchange()).with("order.*");
    }

    @Bean
    public Binding topicBindingCreated() {
        return BindingBuilder.bind(topicQueueCreated()).to(topicExchange()).with("#.created");
    }

    @Bean
    public Queue dlxMainQueue() {
        return QueueBuilder.durable(DLQ_MAIN_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                // 不指定时死信沿用原 routing key（这里是队列名），与 DLX 绑定键 "dlq" 不匹配会被丢弃
                .withArgument("x-dead-letter-routing-key", "dlq")
                .withArgument("x-message-ttl", 3000)
                .build();
    }

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX_EXCHANGE);
    }

    @Bean
    public Queue dlxQueue() {
        return new Queue(DLX_QUEUE);
    }

    @Bean
    public Binding dlxBinding() {
        return BindingBuilder.bind(dlxQueue()).to(dlxExchange()).with("dlq");
    }
}
