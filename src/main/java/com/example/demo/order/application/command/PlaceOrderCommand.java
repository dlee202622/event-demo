package com.example.demo.order.application.command;

import java.math.BigDecimal;
import java.util.List;

/**
 * 下单命令。
 *
 * <p>命令对象（Command）只承载「意图 + 参数」，与领域对象解耦：
 * Controller 把 HTTP 请求转成命令，应用服务再把命令转成领域对象。
 * 好处是同一套用例可以被 HTTP、MQ 消费者、定时任务等多种入口复用。</p>
 */
public record PlaceOrderCommand(String customerId, List<Line> lines) {

    public record Line(String productId, String productName, BigDecimal unitPrice, int quantity) {
    }
}
