package com.example.demo.txn.order.controller;

import com.example.demo.txn.order.repository.OrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 订单查询入口 + 全局异常映射。
 * A 自身几乎没有业务失败（协调者的失败都体现在决议里），
 * 这里主要承接 resolve 传错参数这类请求错误。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderQueryController {

    private final OrderRepository orders;

    public OrderQueryController(OrderRepository orders) {
        this.orders = orders;
    }

    /** 最近订单列表：核对五个方案各自的最终状态。 */
    @GetMapping
    public Map<String, Object> recent(@RequestParam(defaultValue = "20") int limit) {
        return Map.of("orders", orders.recent(Math.min(limit, 100)));
    }

    @RestControllerAdvice
    static class Handler {
        @ExceptionHandler(IllegalArgumentException.class)
        public ProblemDetail onBadArgument(IllegalArgumentException e) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
            pd.setTitle("bad_request");
            return pd;
        }
    }
}
