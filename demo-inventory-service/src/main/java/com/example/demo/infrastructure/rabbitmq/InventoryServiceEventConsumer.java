package com.example.demo.infrastructure.rabbitmq;

import com.example.demo.eventdriven.domain.DomainEvent;
import com.example.demo.eventdriven.rabbitmq.EventConsumer;
import com.example.demo.inventory.domain.event.OrderCancelledEvent;
import com.example.demo.inventory.domain.event.OrderCreatedEvent;
import com.example.demo.inventory.domain.event.OrderItem;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 库存服务事件消费者：把 RabbitMQ 消息反序列化为库存上下文本地事件。
 */
@Component
public class InventoryServiceEventConsumer implements EventConsumer {

    @Override
    public DomainEvent deserialize(String eventId, String eventType,
                                   String aggregateId, JsonNode payload) {
        return switch (eventType) {
            case "OrderCreatedEvent" -> deserializeOrderCreatedEvent(eventId, aggregateId, payload);
            case "OrderCancelledEvent" -> deserializeOrderCancelledEvent(eventId, aggregateId, payload);
            default -> null;
        };
    }

    private OrderCreatedEvent deserializeOrderCreatedEvent(String eventId, String aggregateId, JsonNode payload) {
        return new OrderCreatedEvent(
                eventId,
                aggregateId,
                textOrNull(payload, "customerId"),
                deserializeOrderItems(payload.get("items")),
                parseInstant(payload.get("occurredOn"))
        );
    }

    private OrderCancelledEvent deserializeOrderCancelledEvent(String eventId, String aggregateId, JsonNode payload) {
        return new OrderCancelledEvent(
                eventId,
                aggregateId,
                textOrNull(payload, "reason"),
                deserializeOrderItems(payload.get("items")),
                parseInstant(payload.get("occurredOn"))
        );
    }

    private List<OrderItem> deserializeOrderItems(JsonNode itemsNode) {
        List<OrderItem> items = new ArrayList<>();
        if (itemsNode != null && itemsNode.isArray()) {
            for (JsonNode itemNode : itemsNode) {
                items.add(new OrderItem(
                        itemNode.get("productId").asText(),
                        itemNode.get("quantity").asInt()
                ));
            }
        }
        return items;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private Instant parseInstant(JsonNode instantNode) {
        if (instantNode == null || instantNode.isNull()) {
            return Instant.now();
        }
        if (instantNode.isNumber()) {
            return Instant.ofEpochSecond(instantNode.asLong());
        }
        return Instant.parse(instantNode.asText());
    }
}
