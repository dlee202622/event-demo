package com.example.demo.infrastructure.rabbitmq;

import com.example.demo.eventdriven.domain.DomainEvent;
import com.example.demo.eventdriven.rabbitmq.EventConsumer;
import com.example.demo.loyalty.domain.event.Money;
import com.example.demo.loyalty.domain.event.OrderPaidEvent;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 积分服务事件消费者：只处理 OrderPaidEvent。
 */
@Component
public class LoyaltyServiceEventConsumer implements EventConsumer {

    @Override
    public DomainEvent deserialize(String eventId, String eventType,
                                   String aggregateId, JsonNode payload) {
        if (!"OrderPaidEvent".equals(eventType)) {
            return null;
        }
        return new OrderPaidEvent(
                eventId,
                aggregateId,
                payload.get("customerId").asText(),
                deserializeMoney(payload.get("paidAmount")),
                payload.get("paymentId").asText(),
                Instant.parse(payload.get("occurredOn").asText())
        );
    }

    private Money deserializeMoney(JsonNode moneyNode) {
        return new Money(
                new BigDecimal(moneyNode.get("amount").asText()),
                moneyNode.get("currency").asText()
        );
    }
}
