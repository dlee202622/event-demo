package com.example.demo.txn.order.controller;

import com.example.demo.txn.order.core.LocalMessageService;
import com.example.demo.txn.order.repository.OrderRepository;
import com.example.demo.txn.order.repository.OutboxRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 本地消息表入口。最终一致实验：
 *
 * <pre>
 * # 1) 把 C 打成 FAIL 后下单：订单 INIT、消息 PENDING 不断重试
 * curl -X POST 'http://localhost:9010/api/fault?mode=FAIL'
 * curl -X POST 'http://localhost:9008/api/localmsg/order'
 * # 2) 观察消息积压（retry_count 增长，指数退避）
 * curl http://localhost:9008/api/localmsg/messages
 * # 3) 恢复 C，几秒内消息转 SENT、订单转 CONFIRMED
 * curl -X POST 'http://localhost:9010/api/fault?mode=NONE'
 * </pre>
 */
@RestController
@RequestMapping("/api/localmsg")
public class LocalMsgController {

    private final LocalMessageService service;
    private final OutboxRepository outbox;
    private final OrderRepository orders;

    public LocalMsgController(LocalMessageService service, OutboxRepository outbox, OrderRepository orders) {
        this.service = service;
        this.outbox = outbox;
        this.orders = orders;
    }

    /** 最终一致版下单：立即返回（INIT），B/C 由后台重投器补齐。 */
    @PostMapping("/order")
    public Map<String, Object> order(@RequestParam(defaultValue = "1") int qty,
                                     @RequestParam(defaultValue = "10") int points) {
        return service.placeOrder(qty, points);
    }

    /** 消息表现状：state / retry_count / next_retry_at / last_error 一屏看全。 */
    @GetMapping("/messages")
    public Map<String, Object> messages(@RequestParam(defaultValue = "20") int limit) {
        return Map.of("messages", outbox.recent(Math.min(limit, 100)));
    }

    /** 最近订单：核对各方案最终状态。 */
    @GetMapping("/orders")
    public Map<String, Object> ordersList(@RequestParam(defaultValue = "20") int limit) {
        return Map.of("orders", orders.recent(Math.min(limit, 100)));
    }
}
