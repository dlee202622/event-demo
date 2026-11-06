package com.example.demo.mqtt.controller;

import com.example.demo.mqtt.config.MqttConfig.ReceivedMessage;
import com.example.demo.mqtt.service.PubSubService;
import com.example.demo.mqtt.service.QosService;
import com.example.demo.mqtt.service.WildcardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MQTT 演示控制器：暴露三个场景的 HTTP 接口。
 *
 * <p>接口设计原则：
 * <ul>
 *   <li>每个场景有独立的 URL 前缀：{@code /api/mqtt/pubsub}、{@code /api/mqtt/qos}、{@code /api/mqtt/wildcard}</li>
 *   <li>所有接口返回 JSON，便于浏览器直接查看和自动化测试</li>
 *   <li>参数有默认值，减少调用时的认知负担</li>
 * </ul>
 *
 * <p><b>使用顺序</b>：先订阅（subscribe），再发布（publish），最后查询（messages）。
 * MQTT 不持久化普通消息，先发布再订阅会丢失。
 */
@RestController
@RequestMapping("/api/mqtt")
public class MqttDemoController {

    private static final Logger log = LoggerFactory.getLogger(MqttDemoController.class);

    private final PubSubService pubSubService;
    private final QosService qosService;
    private final WildcardService wildcardService;

    public MqttDemoController(PubSubService pubSubService,
                              QosService qosService,
                              WildcardService wildcardService) {
        this.pubSubService = pubSubService;
        this.qosService = qosService;
        this.wildcardService = wildcardService;
    }

    // ==================== 场景 1：基础发布/订阅 ====================

    /**
     * 订阅指定主题。
     *
     * <p>调用后客户端开始接收该主题的消息，直到取消订阅或服务重启。
     *
     * @param topic 要订阅的主题，如 "sensor/temperature"
     * @return 订阅结果，包含分配的 clientId
     */
    @PostMapping("/pubsub/subscribe")
    public ResponseEntity<SubscribeResult> pubsubSubscribe(@RequestParam String topic) {
        if (topic == null || topic.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new SubscribeResult(false, "Topic cannot be empty", null));
        }

        String clientId = pubSubService.subscribe(topic);
        log.info("PubSub subscribe: topic={}, clientId={}", topic, clientId);

        return ResponseEntity.ok(new SubscribeResult(true, "Subscribed to " + topic, clientId));
    }

    /**
     * 发布消息到指定主题。
     *
     * @param topic   目标主题
     * @param payload 消息内容
     * @param qos     QoS 级别，默认 1
     * @return 发布结果
     */
    @PostMapping("/pubsub/publish")
    public ResponseEntity<PublishResult> pubsubPublish(
            @RequestParam String topic,
            @RequestParam String payload,
            @RequestParam(defaultValue = "1") int qos) {

        boolean sent = pubSubService.publish(topic, payload, qos);
        return ResponseEntity.ok(new PublishResult(sent, topic, qos, payload));
    }

    /**
     * 查询指定主题收到的消息。
     *
     * @param topic 主题名称
     * @return 收到的消息列表
     */
    @GetMapping("/pubsub/messages")
    public ResponseEntity<List<ReceivedMessage>> pubsubMessages(@RequestParam String topic) {
        List<ReceivedMessage> messages = pubSubService.getReceivedMessages(topic);
        return ResponseEntity.ok(messages);
    }

    /**
     * 清空指定主题的消息记录。
     */
    @DeleteMapping("/pubsub/messages")
    public ResponseEntity<Void> pubsubClearMessages(@RequestParam String topic) {
        pubSubService.clearMessages(topic);
        return ResponseEntity.noContent().build();
    }

    /**
     * 取消订阅。
     */
    @DeleteMapping("/pubsub/subscribe")
    public ResponseEntity<Void> pubsubUnsubscribe(@RequestParam String topic) {
        pubSubService.unsubscribe(topic);
        return ResponseEntity.noContent().build();
    }

    // ==================== 场景 2：QoS 三级对比 ====================

    /**
     * 订阅 QoS 测试主题。
     *
     * <p>为指定 QoS 级别创建独立的订阅，用于观察不同 QoS 下的消息投递行为。
     *
     * @param qos 订阅时指定的 QoS 级别（0/1/2）
     * @return 订阅的主题名称
     */
    @PostMapping("/qos/subscribe")
    public ResponseEntity<SubscribeResult> qosSubscribe(@RequestParam(defaultValue = "1") int qos) {
        try {
            String topic = qosService.subscribeWithQos(qos);
            return ResponseEntity.ok(new SubscribeResult(true, "Subscribed to QoS test topic", topic));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new SubscribeResult(false, e.getMessage(), null));
        }
    }

    /**
     * 向 QoS 测试主题发布消息。
     *
     * <p>可以分别指定"目标 QoS"（发到哪个主题）和"发送 QoS"（本次发布使用的 QoS）。
     * 通过组合不同的值，可以观察 Broker 的 QoS 降级行为。
     *
     * @param targetQos  目标 QoS 级别，决定发到哪个测试主题（0/1/2）
     * @param payload    消息内容
     * @param publishQos 发送时使用的 QoS 级别（0/1/2），默认与 targetQos 相同
     * @return 发布结果
     */
    @PostMapping("/qos/publish")
    public ResponseEntity<QosPublishResult> qosPublish(
            @RequestParam(defaultValue = "1") int targetQos,
            @RequestParam String payload,
            @RequestParam(required = false) Integer publishQos) {

        // 未指定 publishQos 时默认与 targetQos 相同
        int actualPublishQos = publishQos != null ? publishQos : targetQos;

        try {
            QosService.PublishResult result = qosService.publishWithQos(targetQos, payload, actualPublishQos);
            return ResponseEntity.ok(new QosPublishResult(
                    result.sent(),
                    result.topic(),
                    result.publishQos(),
                    targetQos,
                    result.payload()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new QosPublishResult(false, null, 0, 0, e.getMessage()));
        }
    }

    /**
     * 查询 QoS 测试收到的消息。
     *
     * @param qos QoS 级别（0/1/2）
     * @return 该级别测试主题收到的消息列表
     */
    @GetMapping("/qos/messages")
    public ResponseEntity<List<ReceivedMessage>> qosMessages(@RequestParam int qos) {
        List<ReceivedMessage> messages = qosService.getReceivedMessages(qos);
        return ResponseEntity.ok(messages);
    }

    /**
     * 获取各 QoS 级别的接收计数。
     *
     * <p>用于快速对比：发送 N 条消息后，各 QoS 级别实际收到了多少条。
     *
     * @return key = "qos-0"/"qos-1"/"qos-2", value = 计数
     */
    @GetMapping("/qos/counters")
    public ResponseEntity<Map<String, Integer>> qosCounters() {
        return ResponseEntity.ok(qosService.getQosCounters());
    }

    /**
     * 重置 QoS 测试状态：清空计数器和消息记录。
     */
    @DeleteMapping("/qos/reset")
    public ResponseEntity<Void> qosReset() {
        qosService.reset();
        return ResponseEntity.noContent().build();
    }

    // ==================== 场景 3：通配符订阅 ====================

    /**
     * 订阅通配符主题。
     *
     * <p>支持 {@code +}（单层通配）和 {@code #}（多层通配）。
     *
     * @param topicFilter 主题过滤器，如 "sensor/+/temperature" 或 "sensor/#"
     * @return 订阅结果
     */
    @PostMapping("/wildcard/subscribe")
    public ResponseEntity<SubscribeResult> wildcardSubscribe(@RequestParam String topicFilter) {
        try {
            String clientId = wildcardService.subscribe(topicFilter);
            return ResponseEntity.ok(new SubscribeResult(true, "Subscribed to wildcard filter", clientId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new SubscribeResult(false, e.getMessage(), null));
        }
    }

    /**
     * 发布消息到指定主题（通配符订阅场景）。
     *
     * <p>注意：发布时必须使用精确主题，不能使用通配符。
     *
     * @param topic   精确主题，如 "sensor/room1/temperature"
     * @param payload 消息内容
     * @param qos     QoS 级别，默认 1
     * @return 发布结果
     */
    @PostMapping("/wildcard/publish")
    public ResponseEntity<PublishResult> wildcardPublish(
            @RequestParam String topic,
            @RequestParam String payload,
            @RequestParam(defaultValue = "1") int qos) {

        try {
            boolean sent = wildcardService.publish(topic, payload, qos);
            return ResponseEntity.ok(new PublishResult(sent, topic, qos, payload));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new PublishResult(false, topic, qos, e.getMessage()));
        }
    }

    /**
     * 查询通配符订阅收到的消息。
     *
     * @param topicFilter 订阅时使用的通配符过滤器
     * @return 匹配该过滤器的所有消息
     */
    @GetMapping("/wildcard/messages")
    public ResponseEntity<List<ReceivedMessage>> wildcardMessages(@RequestParam String topicFilter) {
        List<ReceivedMessage> messages = wildcardService.getReceivedMessages(topicFilter);
        return ResponseEntity.ok(messages);
    }

    /**
     * 清空通配符订阅的消息记录。
     */
    @DeleteMapping("/wildcard/messages")
    public ResponseEntity<Void> wildcardClearMessages(@RequestParam String topicFilter) {
        wildcardService.clearMessages(topicFilter);
        return ResponseEntity.noContent().build();
    }

    /**
     * 取消通配符订阅。
     */
    @DeleteMapping("/wildcard/subscribe")
    public ResponseEntity<Void> wildcardUnsubscribe(@RequestParam String topicFilter) {
        wildcardService.unsubscribe(topicFilter);
        return ResponseEntity.noContent().build();
    }

    // ==================== 响应记录定义 ====================

    /**
     * 订阅操作结果。
     */
    public record SubscribeResult(boolean success, String message, String clientId) {}

    /**
     * 发布操作结果。
     */
    public record PublishResult(boolean sent, String topic, int qos, Object payload) {}

    /**
     * QoS 发布结果：额外包含 targetQos 和 publishQos 的对比。
     */
    public record QosPublishResult(
            boolean sent,
            String topic,
            int publishQos,
            int targetQos,
            Object payload
    ) {}
}
