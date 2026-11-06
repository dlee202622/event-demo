package com.example.demo.mqtt.service;

import com.example.demo.mqtt.config.MqttConfig;
import com.example.demo.mqtt.config.MqttConfig.ReceivedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.integration.mqtt.inbound.Mqttv5PahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 场景 2：QoS 三级对比服务。
 */
@Service
public class QosService {

    private static final Logger log = LoggerFactory.getLogger(QosService.class);
    private static final String QOS_TEST_TOPIC_PREFIX = "test/qos/";

    private final MqttConfig mqttConfig;
    private final BeanFactory beanFactory;
    private final MessageChannel outputChannel;
    private final ConcurrentMap<String, List<ReceivedMessage>> receivedMessages;
    private final ConcurrentMap<String, AtomicInteger> qosCounters = new java.util.concurrent.ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Mqttv5PahoMessageDrivenChannelAdapter> adapters = new java.util.concurrent.ConcurrentHashMap<>();

    public QosService(MqttConfig mqttConfig,
                      BeanFactory beanFactory,
                      MessageChannel mqttOutputChannel,
                      ConcurrentMap<String, List<ReceivedMessage>> receivedMessages) {
        this.mqttConfig = mqttConfig;
        this.beanFactory = beanFactory;
        this.outputChannel = mqttOutputChannel;
        this.receivedMessages = receivedMessages;

        qosCounters.put("qos-0", new AtomicInteger(0));
        qosCounters.put("qos-1", new AtomicInteger(0));
        qosCounters.put("qos-2", new AtomicInteger(0));
    }

    public String subscribeWithQos(int qos) {
        if (qos < 0 || qos > 2) {
            throw new IllegalArgumentException("QoS must be 0, 1, or 2");
        }

        String topic = QOS_TEST_TOPIC_PREFIX + qos;

        if (adapters.containsKey(qos)) {
            return topic;
        }

        String clientId = mqttConfig.generateClientId("qos" + qos);

        try {
            Mqttv5PahoMessageDrivenChannelAdapter adapter = new Mqttv5PahoMessageDrivenChannelAdapter(
                    mqttConfig.getBrokerUrl(),
                    clientId,
                    topic
            );
            adapter.setBeanFactory(beanFactory);
            adapter.setQos(qos);
            adapter.setAutoStartup(true);
            adapter.setOutputChannel(createQosMessageHandler(topic, qos));
            adapter.afterPropertiesSet();
            adapter.start();
            adapters.put(qos, adapter);

            log.info("Subscribed to QoS test topic [{}] with QoS={}", topic, qos);
        } catch (Exception e) {
            log.error("Failed to subscribe with QoS [{}]", qos, e);
            throw new RuntimeException("Subscribe failed", e);
        }

        return topic;
    }

    private MessageChannel createQosMessageHandler(String topic, int subscriptionQos) {
        return (message, timeout) -> {
            int receivedQos = message.getHeaders().containsKey(MqttHeaders.RECEIVED_QOS)
                    ? (int) message.getHeaders().get(MqttHeaders.RECEIVED_QOS)
                    : 0;

            // payload 可能是 byte[] 或 String，统一转为 String
            Object rawPayload = message.getPayload();
            String payloadStr = rawPayload instanceof byte[]
                    ? new String((byte[]) rawPayload)
                    : rawPayload.toString();

            ReceivedMessage msg = new ReceivedMessage(
                    topic,
                    payloadStr,
                    receivedQos,
                    Instant.now().toEpochMilli()
            );

            receivedMessages.computeIfAbsent(topic, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(msg);

            String counterKey = "qos-" + receivedQos;
            AtomicInteger counter = qosCounters.get(counterKey);
            if (counter != null) {
                counter.incrementAndGet();
            }

            log.info("QoS test received: topic={}, subQos={}, receivedQos={}", topic, subscriptionQos, receivedQos);
            return true;
        };
    }

    public PublishResult publishWithQos(int targetQos, String payload, int publishQos) {
        String topic = QOS_TEST_TOPIC_PREFIX + targetQos;

        Message<String> message = MessageBuilder.withPayload(payload)
                .setHeader(MqttHeaders.TOPIC, topic)
                .setHeader(MqttHeaders.QOS, publishQos)
                .build();

        boolean sent = outputChannel.send(message, 5000);
        log.info("QoS publish: topic={}, publishQos={}, sent={}", topic, publishQos, sent);

        return new PublishResult(topic, publishQos, payload, sent);
    }

    public List<ReceivedMessage> getReceivedMessages(int qos) {
        return receivedMessages.getOrDefault(QOS_TEST_TOPIC_PREFIX + qos, Collections.emptyList());
    }

    public java.util.Map<String, Integer> getQosCounters() {
        java.util.Map<String, Integer> result = new java.util.HashMap<>();
        qosCounters.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }

    public void reset() {
        qosCounters.values().forEach(c -> c.set(0));
        receivedMessages.keySet().removeIf(k -> k.startsWith(QOS_TEST_TOPIC_PREFIX));
    }

    public record PublishResult(String topic, int publishQos, String payload, boolean sent) {}
}
