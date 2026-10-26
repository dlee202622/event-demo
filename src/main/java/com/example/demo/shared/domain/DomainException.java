package com.example.demo.shared.domain;

/**
 * 领域异常：表示业务规则被违反（不是技术故障）。
 *
 * <p>与 IllegalArgumentException 的区别在于语义：前者是「业务上不允许」，
 * 在 REST 层通常映射为 400 + 可读的业务错误码；后者多用于编程错误。</p>
 */
public class DomainException extends RuntimeException {

    public DomainException(String message) {
        super(message);
    }

    public DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
