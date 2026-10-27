package com.example.demo.infrastructure.rabbitmq;

import com.example.demo.eventdriven.domain.DomainEvent;
import com.example.demo.eventdriven.rabbitmq.EventConsumer;
import com.example.demo.inventory.domain.event.InventoryReservationFailedEvent;
import com.example.demo.order.domain.event.*;
import com.example.demo.order.domain.model.Money;
import com.example.demo.order.domain.model.OrderItem;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Demo Core 事件消费者实现。
 * 
 * <p>负责将 RabbitMQ 消息反序列化为领域事件对象。</p>
 */
@Component
public class DemoCoreEventConsumer implements EventConsumer {
    
    @Override
    public DomainEvent deserialize(String eventId, String eventType, 
                                   String aggregateId, JsonNode payload) {
        return switch (eventType) {
            case "OrderCreatedEvent" -> deserializeOrderCreatedEvent(eventId, aggregateId, payload);
            case "OrderConfirmedEvent" -> deserializeOrderConfirmedEvent(eventId, aggregateId, payload);
            case "OrderPaidEvent" -> deserializeOrderPaidEvent(eventId, aggregateId, payload);
            case "OrderCancelledEvent" -> deserializeOrderCancelledEvent(eventId, aggregateId, payload);
            case "InventoryReservationFailedEvent" -> 
                deserializeInventoryReservationFailedEvent(eventId, aggregateId, payload);
            default -> null;
        };
    }
    
    private OrderCreatedEvent deserializeOrderCreatedEvent(String eventId, String aggregateId, JsonNode payload) {
        String customerId = payload.get("customerId").asText();
        Money totalAmount = deserializeMoney(payload.get("totalAmount"));
        List<OrderItem> items = deserializeOrderItems(payload.get("items"));
        Instant occurredOn = deserializeInstant(payload.get("occurredOn"));
        
        return new OrderCreatedEvent(eventId, aggregateId, customerId, totalAmount, items, occurredOn);
    }
    
    private OrderConfirmedEvent deserializeOrderConfirmedEvent(String eventId, String aggregateId, JsonNode payload) {
        Instant occurredOn = deserializeInstant(payload.get("occurredOn"));
        return new OrderConfirmedEvent(eventId, aggregateId, occurredOn);
    }
    
    private OrderPaidEvent deserializeOrderPaidEvent(String eventId, String aggregateId, JsonNode payload) {
        String customerId = payload.get("customerId").asText();
        Money paidAmount = deserializeMoney(payload.get("paidAmount"));
        String paymentId = payload.get("paymentId").asText();
        Instant occurredOn = deserializeInstant(payload.get("occurredOn"));
        
        return new OrderPaidEvent(eventId, aggregateId, customerId, paidAmount, paymentId, occurredOn);
    }
    
    private OrderCancelledEvent deserializeOrderCancelledEvent(String eventId, String aggregateId, JsonNode payload) {
        String reason = payload.get("reason").asText();
        List<OrderItem> items = deserializeOrderItems(payload.get("items"));
        Instant occurredOn = deserializeInstant(payload.get("occurredOn"));
        
        return new OrderCancelledEvent(eventId, aggregateId, reason, items, occurredOn);
    }
    
    private InventoryReservationFailedEvent deserializeInventoryReservationFailedEvent(
            String eventId, String aggregateId, JsonNode payload) {
        String orderId = payload.get("orderId").asText();
        String productId = payload.get("productId").asText();
        int requiredQuantity = payload.get("requiredQuantity").asInt();
        int availableQuantity = payload.get("availableQuantity").asInt();
        String reason = payload.get("reason").asText();
        Instant occurredOn = deserializeInstant(payload.get("occurredOn"));
        
        return new InventoryReservationFailedEvent(
                eventId, orderId, productId, requiredQuantity, availableQuantity, reason, occurredOn
        );
    }
    
    private Money deserializeMoney(JsonNode moneyNode) {
        BigDecimal amount = new BigDecimal(moneyNode.get("amount").asDouble());
        String currency = moneyNode.get("currency").asText();
        return new Money(amount, currency);
    }
    
    private List<OrderItem> deserializeOrderItems(JsonNode itemsNode) {
        List<OrderItem> items = new ArrayList<>();
        if (itemsNode != null && itemsNode.isArray()) {
            for (JsonNode itemNode : itemsNode) {
                String productId = itemNode.get("productId").asText();
                String productName = itemNode.has("productName") ? 
                    itemNode.get("productName").asText() : "商品-" + productId;
                Money unitPrice = deserializeMoney(itemNode.get("unitPrice"));
                int quantity = itemNode.get("quantity").asInt();
                items.add(new OrderItem(productId, productName, unitPrice, quantity));
            }
        }
        return items;
    }
    
    private Instant deserializeInstant(JsonNode instantNode) {
        if (instantNode.isNumber()) {
            // Handle timestamp (seconds since epoch)
            return Instant.ofEpochSecond(instantNode.asLong());
        } else if (instantNode.isTextual()) {
            // Handle ISO-8601 string
            return Instant.parse(instantNode.asText());
        }
        throw new IllegalArgumentException("Cannot deserialize Instant from: " + instantNode);
    }
}
