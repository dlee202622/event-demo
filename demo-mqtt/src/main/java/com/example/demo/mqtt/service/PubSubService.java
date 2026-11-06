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
 * 场景 1：基础发布/订阅服务。
 */
@Service
public class PubSubService {

    private static final Logger log = LoggerFactory.getLogger(PubSubService.class);

    private final MqttConfig mqttConfig;
    private final BeanFactory beanFactory;
    private final MessageChannel outputChannel;
    private final ConcurrentMap<String, List<ReceivedMessage>> receivedMessages;
    private final ConcurrentMap<String, Mqttv5PahoMessageDrivenChannelAdapter> adapters = new java.util.concurrent.ConcurrentHashMap<>();

    public PubSubService(MqttConfig mqttConfig,
                         BeanFactory beanFactory,
                         MessageChannel mqttOutputChannel,
                         ConcurrentMap<String, List<ReceivedMessage>> receivedMessages) {
        this.mqttConfig = mqttConfig;
        this.beanFactory = beanFactory;
        this.outputChannel = mqttOutputChannel;
        this.receivedMessages = receivedMessages;
    }

    public String subscribe(String topic) {
        if (adapters.containsKey(topic)) {
            return "existing";
        }

        String clientId = mqttConfig.generateClientId("sub-" + topic.hashCode());

        try {
            Mqttv5PahoMessageDrivenChannelAdapter adapter = new Mqttv5PahoMessageDrivenChannelAdapter(
                    mqttConfig.getBrokerUrl(),
                    clientId,
                    topic
            );
            // 设置 BeanFactory，让 adapter 能访问 Spring 上下文
            adapter.setBeanFactory(beanFactory);
            adapter.setAutoStartup(true);
            adapter.setOutputChannel(createMessageHandler(topic));
            adapter.afterPropertiesSet();
            adapter.start();

            adapters.put(topic, adapter);
            log.info("Subscribed to topic [{}] with clientId [{}]", topic, clientId);
        } catch (Exception e) {
            log.error("Failed to subscribe to topic [{}]", topic, e);
            throw new RuntimeException("Subscribe failed", e);
        }

        return clientId;
    }

    private MessageChannel createMessageHandler(String topic) {
        return (message, timeout) -> {
            int qos = message.getHeaders().containsKey(MqttHeaders.RECEIVED_QOS)
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
                    qos,
                    Instant.now().toEpochMilli()
            );

            receivedMessages.computeIfAbsent(topic, k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(msg);

            log.info("Received message on [{}]: qos={}, payload={}", topic, qos, msg.payload());
            return true;
        };
    }

    public boolean publish(String topic, String payload, int qos) {
        Message<String> message = MessageBuilder.withPayload(payload)
                .setHeader(MqttHeaders.TOPIC, topic)
                .setHeader(MqttHeaders.QOS, qos)
                .build();

        boolean sent = outputChannel.send(message, 5000);
        log.info("Published to [{}]: qos={}, payload={}, sent={}", topic, qos, payload, sent);
        return sent;
    }

    public List<ReceivedMessage> getReceivedMessages(String topic) {
        return receivedMessages.getOrDefault(topic, Collections.emptyList());
    }

    public void clearMessages(String topic) {
        receivedMessages.remove(topic);
    }

    public void unsubscribe(String topic) {
        Mqttv5PahoMessageDrivenChannelAdapter adapter = adapters.remove(topic);
        if (adapter != null) {
            adapter.stop();
            adapter.destroy();
            log.info("Unsubscribed from topic [{}]", topic);
        }
    }
}
