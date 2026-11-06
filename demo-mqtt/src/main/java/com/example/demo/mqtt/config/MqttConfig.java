package com.example.demo.mqtt.config;

import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.mqtt.outbound.Mqttv5PahoMessageHandler;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * MQTT 配置：使用简化的 URL-based 构造函数。
 */
@Configuration
public class MqttConfig {

    @Value("${mqtt.broker-url}")
    private String brokerUrl;

    @Value("${mqtt.client-id-prefix}")
    private String clientIdPrefix;

    @Value("${mqtt.connection-timeout:10}")
    private int connectionTimeout;

    @Value("${mqtt.keep-alive:60}")
    private int keepAlive;

    @Value("${mqtt.automatic-reconnect:true}")
    private boolean automaticReconnect;

    @Value("${mqtt.clean-session:true}")
    private boolean cleanSession;

    @Bean
    public ConcurrentMap<String, java.util.List<ReceivedMessage>> receivedMessages() {
        return new ConcurrentHashMap<>();
    }

    public MqttConnectionOptions createOptions() throws Exception {
        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setServerURIs(new String[]{brokerUrl});
        options.setConnectionTimeout(connectionTimeout);
        options.setKeepAliveInterval(keepAlive);
        options.setAutomaticReconnect(automaticReconnect);
        options.setCleanStart(cleanSession);
        return options;
    }

    public String generateClientId(String purpose) {
        return clientIdPrefix + purpose + "-" + System.nanoTime();
    }

    public String getBrokerUrl() {
        return brokerUrl;
    }

    @Bean
    public MessageHandler mqttOutboundHandler() throws Exception {
        String clientId = generateClientId("publisher");
        Mqttv5PahoMessageHandler handler = new Mqttv5PahoMessageHandler(brokerUrl, clientId);
        handler.setDefaultQos(1);
        handler.setAsync(true);
        return handler;
    }

    @Bean
    public IntegrationFlow mqttOutboundFlow(MessageHandler mqttOutboundHandler) {
        return IntegrationFlow.from("mqttOutputChannel")
                .handle(mqttOutboundHandler)
                .get();
    }

    @Bean
    public MessageChannel mqttOutputChannel() {
        return new DirectChannel();
    }

    public record ReceivedMessage(String topic, String payload, int qos, long timestamp) {}
}
