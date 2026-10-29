package com.example.demo.concurrency;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文加载测试：确认 application.yaml 能绑定 DemoSettings、控制器能完成注入。
 * 用 NONE 模式，避免测试时占用 9007 端口。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ConcurrencyDemoApplicationTest {

    @Test
    void contextLoads() {
    }
}
