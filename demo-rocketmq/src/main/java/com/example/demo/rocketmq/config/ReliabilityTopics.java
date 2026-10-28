package com.example.demo.rocketmq.config;

/**
 * 可靠性场景使用的 Topic 与 ConsumerGroup 常量。
 *
 * <p>注解里的 topic / consumerGroup 必须是编译期常量，所以集中放在这里，
 * 避免生产者、消费者、控制器三处字符串写错。</p>
 */
public final class ReliabilityTopics {

    public static final String IDEMPOTENT_TOPIC = "demo-idempotent-topic";
    public static final String IDEMPOTENT_GROUP = "demo-idempotent-consumer-group";

    public static final String UNORDERED_TOPIC = "demo-unordered-topic";
    public static final String UNORDERED_GROUP = "demo-unordered-consumer-group";

    public static final String BACKLOG_TOPIC = "demo-backlog-topic";
    public static final String BACKLOG_GROUP = "demo-backlog-consumer-group";

    public static final String PERSISTENCE_TOPIC = "demo-persistence-topic";
    public static final String PERSISTENCE_GROUP = "demo-persistence-consumer-group";

    private ReliabilityTopics() {
    }
}
