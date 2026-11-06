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

/**
 * 场景 3：通配符订阅服务。
 */
@Service
public class WildcardService {

    private static final Logger log = LoggerFactory.getLogger(WildcardService.class);

    private final MqttConfig mqttConfig;
    private final BeanFactory beanFactory;
    private final MessageChannel outputChannel;
    private final ConcurrentMap<String, List<ReceivedMessage>> receivedMessages;
    private final ConcurrentMap<String, Mqttv5PahoMessageDrivenChannelAdapter> adapters = new java.util.concurrent.ConcurrentHashMap<>();

    public WildcardService(MqttConfig mqttConfig,
                           BeanFactory beanFactory,
                           MessageChannel mqttOutputChannel,
                           ConcurrentMap<String, List<ReceivedMessage>> receivedMessages) {
        this.mqttConfig = mqttConfig;
        this.beanFactory = beanFactory;
        this.outputChannel = mqttOutputChannel;
        this.receivedMessages = receivedMessages;
    }

    public String subscribe(String topicFilter) {
        validateTopicFilter(topicFilter);

        if (adapters.containsKey(topicFilter)) {
            return "existing";
        }

        String safeName = topicFilter.replace("+", "plus").replace("#", "hash").replace("/", "_");
        String clientId = mqttConfig.generateClientId("wild-" + safeName);

        try {
            Mqttv5PahoMessageDrivenChannelAdapter adapter = new Mqttv5PahoMessageDrivenChannelAdapter(
                    mqttConfig.getBrokerUrl(),
                    clientId,
                    topicFilter
            );
            adapter.setBeanFactory(beanFactory);
            adapter.setAutoStartup(true);
            adapter.setOutputChannel(createWildcardMessageHandler(topicFilter));
            adapter.afterPropertiesSet();
            adapter.start();

            adapters.put(topicFilter, adapter);
            log.info("Subscribed to wildcard filter [{}]", topicFilter);
        } catch (Exception e) {
            log.error("Failed to subscribe to wildcard filter [{}]", topicFilter, e);
            throw new RuntimeException("Subscribe failed", e);
        }

        return clientId;
    }

    private MessageChannel createWildcardMessageHandler(String topicFilter) {
        return (message, timeout) -> {
            log.debug("Wildcard handler invoked: topicFilter={}, message={}", topicFilter, message);
            
            String actualTopic = message.getHeaders().containsKey(MqttHeaders.RECEIVED_TOPIC)
                    ? message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC, String.class)
                    : "unknown";

            int qos = message.getHeaders().containsKey(MqttHeaders.RECEIVED_QOS)
                    ? (int) message.getHeaders().get(MqttHeaders.RECEIVED_QOS)
                    : 0;

            // payload 可能是 byte[] 或 String，统一转为 String
            Object rawPayload = message.getPayload();
            String payloadStr = rawPayload instanceof byte[]
                    ? new String((byte[]) rawPayload)
                    : rawPayload.toString();

            ReceivedMessage msg = new ReceivedMessage(
                    actualTopic,
                    payloadStr,
                    qos,
                    Instant.now().toEpochMilli()
            );

            receivedMessages.computeIfAbsent(topicFilter, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(msg);

            log.info("Wildcard match: filter={}, actualTopic={}", topicFilter, actualTopic);
            return true;
        };
    }

    public boolean publish(String topic, String payload, int qos) {
        if (topic.contains("+") || topic.contains("#")) {
            throw new IllegalArgumentException("Publish topic cannot contain wildcards");
        }

        Message<String> message = MessageBuilder.withPayload(payload)
                .setHeader(MqttHeaders.TOPIC, topic)
                .setHeader(MqttHeaders.QOS, qos)
                .build();

        boolean sent = outputChannel.send(message, 5000);
        log.info("Wildcard publish: topic={}, qos={}, sent={}", topic, qos, sent);
        return sent;
    }

    public List<ReceivedMessage> getReceivedMessages(String topicFilter) {
        return receivedMessages.getOrDefault(topicFilter, Collections.emptyList());
    }

    public void clearMessages(String topicFilter) {
        receivedMessages.remove(topicFilter);
    }

    public void unsubscribe(String topicFilter) {
        Mqttv5PahoMessageDrivenChannelAdapter adapter = adapters.remove(topicFilter);
        if (adapter != null) {
            adapter.stop();
            adapter.destroy();
            log.info("Unsubscribed from wildcard filter [{}]", topicFilter);
        }
    }

    private void validateTopicFilter(String filter) {
        if (filter == null || filter.isEmpty()) {
            throw new IllegalArgumentException("Topic filter cannot be empty");
        }

        int hashIndex = filter.indexOf('#');
        if (hashIndex >= 0) {
            if (hashIndex != filter.length() - 1) {
                throw new IllegalArgumentException("Wildcard '#' must be the last character");
            }
            if (hashIndex > 0 && filter.charAt(hashIndex - 1) != '/') {
                throw new IllegalArgumentException("Wildcard '#' must be preceded by '/'");
            }
        }

        String[] parts = filter.split("/", -1);
        for (String part : parts) {
            if (part.contains("+") && !part.equals("+")) {
                throw new IllegalArgumentException("Wildcard '+' must occupy an entire level");
            }
        }
    }
}
