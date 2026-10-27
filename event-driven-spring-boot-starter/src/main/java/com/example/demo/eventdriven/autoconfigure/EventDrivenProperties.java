package com.example.demo.eventdriven.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 事件驱动配置属性。
 */
@ConfigurationProperties(prefix = "eventdriven")
public class EventDrivenProperties {
    
    /**
     * 是否启用事件驱动。
     */
    private boolean enabled = true;

    /**
     * 持久化方式：memory | jdbc。
     */
    private String persistence = "memory";
    
    /**
     * Outbox 配置。
     */
    private Outbox outbox = new Outbox();
    
    /**
     * RabbitMQ 配置。
     */
    private RabbitMQ rabbitmq = new RabbitMQ();
    
    public boolean isEnabled() {
        return enabled;
    }
    
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPersistence() {
        return persistence;
    }

    public void setPersistence(String persistence) {
        this.persistence = persistence;
    }
    
    public Outbox getOutbox() {
        return outbox;
    }
    
    public void setOutbox(Outbox outbox) {
        this.outbox = outbox;
    }
    
    public RabbitMQ getRabbitmq() {
        return rabbitmq;
    }
    
    public void setRabbitmq(RabbitMQ rabbitmq) {
        this.rabbitmq = rabbitmq;
    }
    
    public static class Outbox {
        
        /**
         * 是否启用 Outbox Relay。
         */
        private boolean relayEnabled = true;
        
        /**
         * Relay 轮询间隔（毫秒）。
         */
        private long relayIntervalMs = 3000;
        
        /**
         * Relay 初始延迟（毫秒）。
         */
        private long relayInitialDelayMs = 2000;

        /**
         * 每轮认领的最大事件数。
         */
        private int claimBatchSize = 100;
        
        public boolean isRelayEnabled() {
            return relayEnabled;
        }
        
        public void setRelayEnabled(boolean relayEnabled) {
            this.relayEnabled = relayEnabled;
        }
        
        public long getRelayIntervalMs() {
            return relayIntervalMs;
        }
        
        public void setRelayIntervalMs(long relayIntervalMs) {
            this.relayIntervalMs = relayIntervalMs;
        }
        
        public long getRelayInitialDelayMs() {
            return relayInitialDelayMs;
        }
        
        public void setRelayInitialDelayMs(long relayInitialDelayMs) {
            this.relayInitialDelayMs = relayInitialDelayMs;
        }

        public int getClaimBatchSize() {
            return claimBatchSize;
        }

        public void setClaimBatchSize(int claimBatchSize) {
            this.claimBatchSize = claimBatchSize;
        }
    }
    
    public static class RabbitMQ {
        
        /**
         * 交换机名称。
         */
        private String exchange = "event-driven.exchange";
        
        /**
         * 队列名称。
         */
        private String queue = "event-driven.queue";
        
        /**
         * 路由 key 前缀。
         */
        private String routingKeyPrefix = "event";
        
        public String getExchange() {
            return exchange;
        }
        
        public void setExchange(String exchange) {
            this.exchange = exchange;
        }
        
        public String getQueue() {
            return queue;
        }
        
        public void setQueue(String queue) {
            this.queue = queue;
        }
        
        public String getRoutingKeyPrefix() {
            return routingKeyPrefix;
        }
        
        public void setRoutingKeyPrefix(String routingKeyPrefix) {
            this.routingKeyPrefix = routingKeyPrefix;
        }
    }
}
