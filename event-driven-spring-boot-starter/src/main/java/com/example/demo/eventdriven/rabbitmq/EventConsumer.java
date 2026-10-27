package com.example.demo.eventdriven.rabbitmq;

import com.example.demo.eventdriven.domain.DomainEvent;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 事件消费者接口。
 * 
 * <p>实现此接口以自定义事件反序列化逻辑。</p>
 */
public interface EventConsumer {
    
    /**
     * 反序列化事件。
     * 
     * @param eventId 事件 ID
     * @param eventType 事件类型
     * @param aggregateId 聚合根 ID
     * @param payload 事件负载 JSON
     * @return 领域事件对象，如果不支持该类型则返回 null
     */
    DomainEvent deserialize(String eventId, String eventType, String aggregateId, JsonNode payload);
}
