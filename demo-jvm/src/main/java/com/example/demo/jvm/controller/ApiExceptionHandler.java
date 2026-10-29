package com.example.demo.jvm.controller;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常出口：把「用法不对」和「环境缺能力」分开告诉读者。
 *
 * <p>JVM 观测接口最常见的两类失败是：分配大小超过保护上限（调用方的错，400），
 * 以及当前 JVM 不支持某条诊断命令或进程没带对应参数启动（环境的错，500）。
 * 两类都必须在 body 里带上原始信息和补救参数，读者才不用去猜。</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 参数或用法错误：例如分配 MB 数为负、超过 max-allocate-mb、
     * 或未开 NMT 却请求 {@code /native-memory}。
     * 消息里已经写了该怎么改，所以状态码用 400 就够。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", 400,
                "error", String.valueOf(e.getMessage())));
    }

    /**
     * 运行期失败：例如诊断命令不存在、堆转储写盘出错。
     * 这类不是调用方参数问题，转 500 并保留原始消息。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> serverError(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status", 500,
                "error", String.valueOf(e.getMessage())));
    }

    /**
     * 兜底：例如请求的参数根本不是数字。返回 400 而不是 Spring 默认的 500 页面，
     * 这样 curl 的输出仍然是可对照文档的 JSON。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "status", 400,
                "error", e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage())));
    }
}
