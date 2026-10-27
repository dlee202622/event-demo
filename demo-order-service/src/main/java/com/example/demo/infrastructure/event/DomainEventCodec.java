package com.example.demo.infrastructure.event;

import com.example.demo.eventdriven.domain.DomainEvent;
import com.example.demo.order.domain.event.InventoryReservationFailedEvent;
import com.example.demo.order.domain.event.OrderCancelledEvent;
import com.example.demo.order.domain.event.OrderConfirmedEvent;
import com.example.demo.order.domain.event.OrderCreatedEvent;
import com.example.demo.order.domain.event.OrderPaidEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 领域事件的 JSON 编解码：eventType → 具体事件类。 */
@Component
public class DomainEventCodec {

    private final ObjectMapper objectMapper;
    private final Map<String, Class<? extends DomainEvent>> eventTypes = Map.of(
            "OrderCreatedEvent", OrderCreatedEvent.class,
            "OrderConfirmedEvent", OrderConfirmedEvent.class,
            "OrderPaidEvent", OrderPaidEvent.class,
            "OrderCancelledEvent", OrderCancelledEvent.class,
            "InventoryReservationFailedEvent", InventoryReservationFailedEvent.class);

    public DomainEventCodec(ObjectMapper objectMapper) {
        // 值对象上的查询方法（如 Money.isZero）会被序列化成额外属性，
        // 反序列化时忽略这些只读字段。
        this.objectMapper = objectMapper.copy()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public String encode(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("领域事件序列化失败: " + event.eventType(), e);
        }
    }

    public DomainEvent decode(String eventType, String payload) {
        Class<? extends DomainEvent> type = eventTypes.get(eventType);
        if (type == null) {
            throw new IllegalArgumentException("未知事件类型: " + eventType);
        }
        try {
            return objectMapper.readValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("领域事件反序列化失败: " + eventType, e);
        }
    }
}
