package com.example.demo.admin;

import com.example.demo.order.infrastructure.projection.OrderProjectionHandler;
import com.example.demo.order.interfaces.rest.dto.EventResponse;
import com.example.demo.infrastructure.event.DomainEventStore;
import com.example.demo.eventdriven.idempotency.ProcessedEventRegistry;
import com.example.demo.eventdriven.outbox.OutboxEvent;
import com.example.demo.eventdriven.outbox.OutboxRelay;
import com.example.demo.eventdriven.outbox.OutboxStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 演示用诊断接口：把事件驱动链路里「看不见的东西」暴露出来。
 *
 * <p>真实项目里这些能力通常做在运维平台/链路追踪里，本 demo 用几个接口代替，
 * 方便你观察：事件有没有发出来、Outbox 有没有投递、读模型有没有追上。</p>
 */
@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticsController {

    private final DomainEventStore eventStore;
    private final OutboxStore outboxStore;
    private final OutboxRelay outboxRelay;
    private final ProcessedEventRegistry processedEventRegistry;
    private final OrderProjectionHandler projectionHandler;

    public DiagnosticsController(DomainEventStore eventStore,
                                 OutboxStore outboxStore,
                                 OutboxRelay outboxRelay,
                                 ProcessedEventRegistry processedEventRegistry,
                                 OrderProjectionHandler projectionHandler) {
        this.eventStore = eventStore;
        this.outboxStore = outboxStore;
        this.outboxRelay = outboxRelay;
        this.processedEventRegistry = processedEventRegistry;
        this.projectionHandler = projectionHandler;
    }

    @GetMapping("/events")
    public List<EventResponse> events() {
        return eventStore.allEvents().stream().map(EventResponse::from).toList();
    }

    @GetMapping("/outbox")
    public List<OutboxView> outbox() {
        return outboxStore.findAll().stream().map(OutboxView::from).toList();
    }

    @PostMapping("/outbox/flush")
    public Map<String, Object> flushOutbox() {
        int delivered = outboxRelay.flush();
        return Map.of("delivered", delivered, "pending", outboxStore.pendingCount());
    }

    @PostMapping("/projections/rebuild")
    public Map<String, Object> rebuildProjection() {
        int replayed = projectionHandler.rebuild(eventStore.allEvents());
        return Map.of("replayedEvents", replayed);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("eventCount", eventStore.size());
        stats.put("outboxTotal", outboxStore.size());
        stats.put("outboxPending", outboxStore.pendingCount());
        stats.put("processedEvents", processedEventRegistry.size());
        return stats;
    }

    public record OutboxView(String id, String eventId, String eventType, String aggregateId,
                             String status, String payload, java.time.Instant occurredOn) {
        static OutboxView from(OutboxEvent event) {
            return new OutboxView(event.id(), event.eventId(), event.eventType(),
                    event.aggregateId(), event.getStatus(), event.payload(), event.occurredOn());
        }
    }
}
