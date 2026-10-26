package com.example.demo.order.domain.model;

import com.example.demo.shared.domain.DomainException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 金额值对象。
 *
 * <p>用 record 表达值对象：不可变、按值相等。金额 + 币种绑定在一起，
 * 避免把裸 BigDecimal 在系统里到处传递导致的「币种丢失 / 精度失控」。</p>
 */
public record Money(BigDecimal amount, String currency) implements Comparable<Money> {

    public static final String CNY = "CNY";
    public static final Money ZERO = new Money(BigDecimal.ZERO, CNY);

    public Money {
        Objects.requireNonNull(amount, "金额不能为空");
        if (amount.signum() < 0) {
            throw new DomainException("金额不能为负数: " + amount.toPlainString());
        }
        // 统一保留 2 位小数，保证 equals / hashCode 稳定
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        currency = (currency == null || currency.isBlank()) ? CNY : currency.toUpperCase();
    }

    public static Money cny(String amount) {
        return new Money(new BigDecimal(amount), CNY);
    }

    public static Money cny(long amount) {
        return new Money(BigDecimal.valueOf(amount), CNY);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), this.currency);
    }

    public Money multiply(int quantity) {
        if (quantity < 0) {
            throw new DomainException("乘数不能为负数: " + quantity);
        }
        return new Money(this.amount.multiply(BigDecimal.valueOf(quantity)), this.currency);
    }

    public boolean isZero() {
        return this.amount.signum() == 0;
    }

    /** 展示用格式化，如 ¥1,299.00。 */
    public String display() {
        String symbol = CNY.equals(currency) ? "¥" : currency + " ";
        return symbol + amount.toPlainString();
    }

    private void requireSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new DomainException("币种不一致: " + this.currency + " vs " + other.currency);
        }
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return this.amount.compareTo(other.amount);
    }
}
