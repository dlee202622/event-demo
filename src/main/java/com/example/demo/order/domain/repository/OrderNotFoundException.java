package com.example.demo.order.domain.repository;

import com.example.demo.shared.domain.DomainException;

/** 订单不存在（属于业务可预期的异常，REST 层映射为 404）。 */
public class OrderNotFoundException extends DomainException {

    public OrderNotFoundException(String orderId) {
        super("订单不存在: " + orderId);
    }
}
