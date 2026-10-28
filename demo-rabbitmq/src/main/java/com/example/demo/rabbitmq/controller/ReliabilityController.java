package com.example.demo.rabbitmq.controller;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageListenerContainer;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 消息可靠性场景的演示接口：幂等、顺序、积压、持久化。
 *
 * <p>这些接口只负责“制造现象”，真正的处理逻辑在对应的 Consumer 里。
 * 暂停/恢复消费者通过 {@link RabbitListenerEndpointRegistry} 按监听容器 id 控制，
 * 队列深度通过 {@link AmqpAdmin#getQueueInfo(String)} 查询。</p>
 */
@RestController
@RequestMapping("/api/rabbitmq")
public class ReliabilityController {

    /** 单次批量发送上限，避免误操作把本地 Broker 打满。 */
    private static final int MAX_BATCH = 1000;

    private static final String BACKLOG_CONTAINER = "backlogConsumer";
    private static final String PERSISTENCE_CONTAINER = "persistenceConsumer";

    private final RabbitTemplate rabbitTemplate;
    private final RabbitListenerEndpointRegistry listenerRegistry;
    private final AmqpAdmin amqpAdmin;

    public ReliabilityController(RabbitTemplate rabbitTemplate,
                                 RabbitListenerEndpointRegistry listenerRegistry,
                                 AmqpAdmin amqpAdmin) {
        this.rabbitTemplate = rabbitTemplate;
        this.listenerRegistry = listenerRegistry;
        this.amqpAdmin = amqpAdmin;
    }

    // ------------------------------------------------------------------
    // 1. 幂等
    // ------------------------------------------------------------------

    /**
     * 发送幂等演示消息。
     *
     * <p>{@code duplicate=true} 时连发两条相同的业务 ID，模拟 Broker 重投；
     * 消费端只会处理一次，第二条打印 duplicate ignored。</p>
     */
    @PostMapping("/idempotent")
    public Map<String, String> sendIdempotent(
            @RequestParam(defaultValue = "ID-1") String messageId,
            @RequestParam(defaultValue = "true") boolean duplicate) {
        int times = duplicate ? 2 : 1;
        for (int i = 0; i < times; i++) {
            // 消息体就是业务唯一 ID，跨重投保持不变；不要依赖 Broker 生成的 messageId
            rabbitTemplate.convertAndSend("", RabbitMQConfig.IDEMPOTENT_QUEUE, messageId);
        }
        return Map.of(
                "queue", RabbitMQConfig.IDEMPOTENT_QUEUE,
                "messageId", messageId,
                "sent", String.valueOf(times));
    }

    // ------------------------------------------------------------------
    // 2. 顺序
    // ------------------------------------------------------------------

    /**
     * 向“单消费者”队列发送编号消息，日志应按 step-1、step-2 ... 递增。
     *
     * <p>必须把整批发送放进 {@link RabbitTemplate#invoke} 里：开启 publisher confirm 后，
     * 一个 channel 在确认返回前不会归还连接池，连续 convertAndSend 可能落到多个 channel 上，
     * 而 Broker 只保证**同一 channel 内**的发布顺序。invoke 会独占一个 channel 并在退出前
     * 等待确认，因此这一批消息的入队顺序才真正稳定。</p>
     *
     * <p>消费端还需要 {@code concurrency = 1}（见 {@code OrderedConsumer}），
     * 生产端有序 + 单队列单消费者，才构成完整的顺序消费方案。</p>
     */
    @PostMapping("/ordered")
    public Map<String, String> sendOrdered(@RequestParam(defaultValue = "10") int count) {
        int total = clamp(count);
        rabbitTemplate.invoke(operations -> {
            for (int i = 1; i <= total; i++) {
                operations.convertAndSend("", RabbitMQConfig.ORDERED_QUEUE, "step-" + i);
            }
            return null;
        });
        return Map.of(
                "queue", RabbitMQConfig.ORDERED_QUEUE,
                "sent", String.valueOf(total),
                "consumers", "1");
    }

    /** 反例：同样的编号消息发给并发消费者，日志顺序会被打乱。 */
    @PostMapping("/unordered")
    public Map<String, String> sendUnordered(@RequestParam(defaultValue = "10") int count) {
        int total = clamp(count);
        for (int i = 1; i <= total; i++) {
            rabbitTemplate.convertAndSend("", RabbitMQConfig.UNORDERED_QUEUE, "step-" + i);
        }
        return Map.of(
                "queue", RabbitMQConfig.UNORDERED_QUEUE,
                "sent", String.valueOf(total),
                "consumers", "4");
    }

    // ------------------------------------------------------------------
    // 3. 积压
    // ------------------------------------------------------------------

    /**
     * 批量发送积压演示消息。
     *
     * <p>建议顺序：先 /backlog/pause，再 /backlog/send?count=200，
     * 用 /backlog/status 观察 messageCount，最后 /backlog/resume 消费。</p>
     */
    @PostMapping("/backlog/send")
    public Map<String, String> sendBacklog(@RequestParam(defaultValue = "100") int count) {
        int total = clamp(count);
        for (int i = 1; i <= total; i++) {
            rabbitTemplate.convertAndSend("", RabbitMQConfig.BACKLOG_QUEUE, "backlog-" + i);
        }
        return Map.of(
                "queue", RabbitMQConfig.BACKLOG_QUEUE,
                "sent", String.valueOf(total),
                "next", "GET /api/rabbitmq/backlog/status 查看堆积量");
    }

    /** 暂停积压消费者：容器停止拉取，消息会在队列里堆积。 */
    @PostMapping("/backlog/pause")
    public Map<String, String> pauseBacklog() {
        return setContainerRunning(BACKLOG_CONTAINER, false);
    }

    /** 恢复积压消费者：容器重新拉取，堆积消息被逐步消费。 */
    @PostMapping("/backlog/resume")
    public Map<String, String> resumeBacklog() {
        return setContainerRunning(BACKLOG_CONTAINER, true);
    }

    /** 查看队列深度与消费者运行状态。 */
    @GetMapping("/backlog/status")
    public Map<String, String> backlogStatus() {
        return queueStatus(RabbitMQConfig.BACKLOG_QUEUE, BACKLOG_CONTAINER);
    }

    // ------------------------------------------------------------------
    // 4. 持久化
    // ------------------------------------------------------------------

    /**
     * 发送持久化演示消息。
     *
     * <p>{@code persistent=true} 使用 {@link MessageDeliveryMode#PERSISTENT}，
     * 消息会写磁盘；{@code false} 只存在内存。两者都进同一个 durable 队列，
     * 重启 Broker 后对比 /persistence/status 的 messageCount 即可看到差异。</p>
     *
     * <p>验证步骤：pause → send → {@code docker compose restart rabbitmq} →
     * status（持久化消息还在）→ resume。注意是 restart 而不是 down/up，
     * 后者会重建容器并清空未挂载数据卷的 Broker 数据。</p>
     */
    @PostMapping("/persistence/send")
    public Map<String, String> sendPersistence(
            @RequestParam(defaultValue = "3") int count,
            @RequestParam(defaultValue = "true") boolean persistent) {
        int total = clamp(count);
        MessageDeliveryMode deliveryMode = persistent
                ? MessageDeliveryMode.PERSISTENT
                : MessageDeliveryMode.NON_PERSISTENT;
        for (int i = 1; i <= total; i++) {
            MessageProperties properties = new MessageProperties();
            properties.setDeliveryMode(deliveryMode);
            properties.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
            properties.setMessageId("persist-" + i + "-" + System.currentTimeMillis());
            Message message = new Message(("persist-" + i).getBytes(StandardCharsets.UTF_8), properties);
            // 空字符串是默认交换机，routing key 写队列名
            rabbitTemplate.send("", RabbitMQConfig.PERSISTENCE_QUEUE, message);
        }
        return Map.of(
                "queue", RabbitMQConfig.PERSISTENCE_QUEUE,
                "sent", String.valueOf(total),
                "persistent", String.valueOf(persistent),
                "next", "docker compose restart rabbitmq 后查看 /persistence/status");
    }

    /** 暂停持久化消费者，避免消息发送后被立刻消费掉。 */
    @PostMapping("/persistence/pause")
    public Map<String, String> pausePersistence() {
        return setContainerRunning(PERSISTENCE_CONTAINER, false);
    }

    /** 恢复持久化消费者：重启 Broker 后调用它，验证消息仍能投递。 */
    @PostMapping("/persistence/resume")
    public Map<String, String> resumePersistence() {
        return setContainerRunning(PERSISTENCE_CONTAINER, true);
    }

    /** 查看持久化队列深度与消费者状态。 */
    @GetMapping("/persistence/status")
    public Map<String, String> persistenceStatus() {
        return queueStatus(RabbitMQConfig.PERSISTENCE_QUEUE, PERSISTENCE_CONTAINER);
    }

    // ------------------------------------------------------------------
    // 公共辅助
    // ------------------------------------------------------------------

    /** 批量参数做边界收敛，避免一次请求发送过多消息。 */
    private int clamp(int count) {
        return Math.max(1, Math.min(count, MAX_BATCH));
    }

    private Map<String, String> setContainerRunning(String containerId, boolean running) {
        MessageListenerContainer container = listenerRegistry.getListenerContainer(containerId);
        if (container == null) {
            return Map.of("container", containerId, "error", "listener container not found");
        }
        if (running) {
            container.start();
        } else {
            container.stop();
        }
        return Map.of("container", containerId, "running", String.valueOf(container.isRunning()));
    }

    private Map<String, String> queueStatus(String queue, String containerId) {
        QueueInformation info = amqpAdmin.getQueueInfo(queue);
        String messageCount = info == null ? "unknown" : String.valueOf(info.getMessageCount());
        MessageListenerContainer container = listenerRegistry.getListenerContainer(containerId);
        boolean running = container != null && container.isRunning();
        return Map.of(
                "queue", queue,
                "messageCount", messageCount,
                "consumerRunning", String.valueOf(running));
    }
}
