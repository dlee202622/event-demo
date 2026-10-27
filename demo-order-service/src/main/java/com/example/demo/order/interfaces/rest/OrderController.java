package com.example.demo.order.interfaces.rest;

import com.example.demo.order.application.OrderApplicationService;
import com.example.demo.order.application.query.OrderQueryService;
import com.example.demo.order.application.query.OrderView;
import com.example.demo.order.domain.model.OrderId;
import com.example.demo.order.interfaces.rest.dto.EventResponse;
import com.example.demo.order.interfaces.rest.dto.PlaceOrderRequest;
import com.example.demo.infrastructure.event.DomainEventStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * 订单 REST 接口（接口层 / 防腐层）。
 *
 * <p>职责只有三件事：协议转换（HTTP ↔ 命令）、调用应用服务、把结果转成响应。
 * 不写任何业务判断 —— 业务流程都在 {@link OrderApplicationService} 与聚合里。</p>
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderApplicationService orderApplicationService;
    private final OrderQueryService orderQueryService;
    private final DomainEventStore domainEventStore;

    public OrderController(OrderApplicationService orderApplicationService,
                           OrderQueryService orderQueryService,
                           DomainEventStore domainEventStore) {
        this.orderApplicationService = orderApplicationService;
        this.orderQueryService = orderQueryService;
        this.domainEventStore = domainEventStore;
    }

    /** 下单。 */
    @PostMapping
    public ResponseEntity<OrderView> placeOrder(@RequestBody PlaceOrderRequest request) {
        OrderId orderId = orderApplicationService.placeOrder(request.toCommand());
        OrderView view = orderQueryService.findByOrderId(orderId.value());
        return ResponseEntity
                .created(URI.create("/api/orders/" + orderId.value()))
                .body(view);
    }

    /** 订单列表（走读模型）。 */
    @GetMapping
    public List<OrderView> list() {
        return orderQueryService.findAll();
    }

    /** 订单详情（走读模型）。 */
    @GetMapping("/{orderId}")
    public OrderView detail(@PathVariable String orderId) {
        return orderQueryService.findByOrderId(orderId);
    }

    /** 确认订单。 */
    @PostMapping("/{orderId}/confirm")
    public OrderView confirm(@PathVariable String orderId) {
        orderApplicationService.confirmOrder(orderId);
        return orderQueryService.findByOrderId(orderId);
    }

    /** 支付订单。 */
    @PostMapping("/{orderId}/pay")
    public OrderView pay(@PathVariable String orderId, @RequestBody(required = false) PayRequest request) {
        String paymentId = (request == null || request.paymentId() == null || request.paymentId().isBlank())
                ? "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase()
                : request.paymentId();
        orderApplicationService.payOrder(orderId, paymentId);
        return orderQueryService.findByOrderId(orderId);
    }

    /** 取消订单。 */
    @PostMapping("/{orderId}/cancel")
    public OrderView cancel(@PathVariable String orderId,
                            @RequestBody(required = false) CancelRequest request) {
        orderApplicationService.cancelOrder(orderId,
                request == null ? null : request.reason());
        return orderQueryService.findByOrderId(orderId);
    }

    /** 订单事件流：审计 / 排障 / 事件溯源入口。 */
    @GetMapping("/{orderId}/events")
    public List<EventResponse> events(@PathVariable String orderId) {
        return domainEventStore.eventsOf(orderId).stream()
                .map(EventResponse::from)
                .toList();
    }

    /** 支付请求体。 */
    public record PayRequest(String paymentId) {
    }

    /** 取消请求体。 */
    public record CancelRequest(String reason) {
    }
}
