package com.example.demo.order.interfaces.rest.dto;

import com.example.demo.order.application.command.PlaceOrderCommand;

import java.math.BigDecimal;
import java.util.List;

/** 下单请求体（接口层 DTO，不直接暴露领域模型）。 */
public record PlaceOrderRequest(String customerId, List<Item> items) {

    public record Item(String productId, String productName, BigDecimal unitPrice, int quantity) {
    }

    /** 接口层 DTO → 应用层命令。 */
    public PlaceOrderCommand toCommand() {
        List<PlaceOrderCommand.Line> lines = items == null ? List.of() : items.stream()
                .map(item -> new PlaceOrderCommand.Line(
                        item.productId(), item.productName(), item.unitPrice(), item.quantity()))
                .toList();
        return new PlaceOrderCommand(customerId, lines);
    }
}
