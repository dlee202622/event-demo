package com.example.demo.redis.support;

import java.util.List;

/**
 * 一个数据类型案例的完整结果。
 */
public record DemoResult(String type, String title, String scenario,
                         List<DemoStep> steps, List<String> notes) {
}
