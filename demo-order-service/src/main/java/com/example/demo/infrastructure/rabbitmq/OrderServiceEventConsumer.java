package com.example.demo.infrastructure.rabbitmq;

import com.example.demo.eventdriven.domain.DomainEvent;
import com.example.demo.eventdriven.rabbitmq.EventConsumer;
import com.example.demo.order.domain.event.InventoryReservationFailedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
public class OrderServiceEventConsumer implements EventConsumer {

    @Override
    public DomainEvent deserialize(String eventId, String eventType,
                                   String aggregateId, JsonNode payload) {
        return switch (eventType) {
            case "InventoryReservationFailedEvent" ->
                deserializeInventoryReservationFailedEvent(eventId, aggregateId, payload);
            default -> null;  // order-service 只消费库存失败事件
        };
    }

    private InventoryReservationFailedEvent deserializeInventoryReservationFailedEvent(
            String eventId, String aggregateId, JsonNode payload) {
        return new InventoryReservationFailedEvent(
                eventId,
                payload.get("orderId").asText(),
                payload.get("productId").asText(),
                payload.get("requiredQuantity").asInt(),
                payload.get("availableQuantity").asInt(),
                payload.get("reason").asText(),
                Instant.parse(payload.get("occurredOn").asText())
        );
    }
}
