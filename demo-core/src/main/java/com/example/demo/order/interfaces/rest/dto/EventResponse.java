package com.example.demo.order.interfaces.rest.dto;

import com.example.demo.eventdriven.domain.DomainEvent;

import java.time.Instant;

/** 事件流视图（用于演示事件溯源 / 审计查询）。 */
public record EventResponse(
        String eventId,
        String eventType,
        String aggregateId,
        Instant occurredOn,
        String payload
) {
    public static EventResponse from(DomainEvent event) {
        return new EventResponse(
                event.eventId(),
                event.eventType(),
                event.aggregateId(),
                event.occurredOn(),
                event.toString());
    }
}
