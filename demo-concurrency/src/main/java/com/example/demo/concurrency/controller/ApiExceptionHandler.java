package com.example.demo.concurrency.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 参数错误统一返回 400，环境/实现问题返回 500，方便 curl 直接看到原因。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 参数越界或枚举值非法：例如 mode=abc、threads=9999。
     * 错误消息里会列出合法范围/可选值，调用方不用猜。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", 400,
                "error", String.valueOf(e.getMessage())));
    }

    /**
     * 运行期状态异常：例如实验内部等待超时、锁状态不符合预期。
     * 这类不是调用方参数问题，转 500 并保留消息。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> serverError(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status", 500,
                "error", String.valueOf(e.getMessage())));
    }
}
