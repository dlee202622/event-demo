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

    // RocketMQ 没有消息优先级：按优先级拆 Topic + 独立消费者组，靠资源隔离模拟
    public static final String PRIORITY_HIGH_TOPIC = "demo-priority-high-topic";
    public static final String PRIORITY_HIGH_GROUP = "demo-priority-high-consumer-group";
    public static final String PRIORITY_NORMAL_TOPIC = "demo-priority-normal-topic";
    public static final String PRIORITY_NORMAL_GROUP = "demo-priority-normal-consumer-group";
    public static final String PRIORITY_LOW_TOPIC = "demo-priority-low-topic";
    public static final String PRIORITY_LOW_GROUP = "demo-priority-low-consumer-group";

    private ReliabilityTopics() {
    }
}
