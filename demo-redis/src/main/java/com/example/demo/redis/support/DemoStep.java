package com.example.demo.redis.support;

/**
 * 演示步骤：一条命令 + 实际返回 + 说明。
 */
public record DemoStep(String command, Object result, String note) {
}
