package com.example.demo.redis.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 参数错误统一返回 400，方便 curl / 文档直接看原因。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 业务校验失败：例如传入的执行次数为负、商品 id 不存在。
     * 统一转 400，body 里带上原始异常信息，方便对照文档排查。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", 400,
                "error", String.valueOf(e.getMessage())));
    }

    /**
     * 运行期状态异常：例如 Redis 连接不上、Lua 脚本返回了预期外的结果。
     * 这类是环境或实现问题，不是调用方的错，所以转 500。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> serverError(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status", 500,
                "error", String.valueOf(e.getMessage())));
    }
}
