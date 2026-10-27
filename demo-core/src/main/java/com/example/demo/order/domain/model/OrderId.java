package com.example.demo.order.domain.model;

import com.example.demo.eventdriven.domain.DomainException;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * 订单 ID 值对象。
 *
 * <p>用独立类型而不是裸 String，避免「把 customerId 传给 orderId」这类编译期无法发现的错误；
 * 同时把 ID 生成规则收敛在领域层，仓储/应用层不关心它长什么样。</p>
 */
public record OrderId(String value) {

    private static final String PREFIX = "ORD-";

    public OrderId {
        if (value == null || value.isBlank()) {
            throw new DomainException("订单 ID 不能为空");
        }
        if (!value.startsWith(PREFIX)) {
            throw new DomainException("订单 ID 格式非法，应以 " + PREFIX + " 开头: " + value);
        }
    }

    /** 生成新订单 ID，如 ORD-7F3A9C21。 */
    public static OrderId generate() {
        String suffix = UUID.randomUUID().toString().replace("-", "")
                .substring(0, 8)
                .toUpperCase(Locale.ROOT);
        return new OrderId(PREFIX + suffix);
    }

    /** 从外部输入（URL / 消息体）构造，非法时抛出领域异常。 */
    public static OrderId of(String value) {
        return new OrderId(Objects.requireNonNull(value, "订单 ID 不能为空").trim());
    }

    @Override
    public String toString() {
        return value;
    }
}
