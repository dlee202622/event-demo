package com.example.demo.concurrency;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Java 进程内锁演示入口。
 *
 * <p>六个场景全部在内存里完成，不依赖 Redis / MySQL / Docker：
 * 每个请求自己创建锁和线程，跑完即回收，多个请求之间不会共享状态。</p>
 *
 * <p>{@code @ConfigurationPropertiesScan} 让 {@code DemoSettings} 从
 * {@code demo.concurrency.*} 读取线程数、迭代次数等上限，便于用环境变量隔离验证。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ConcurrencyDemoApplication {

    /**
     * 启动应用；默认端口 9007，可用 {@code SERVER_PORT} 覆盖。
     */
    public static void main(String[] args) {
        SpringApplication.run(ConcurrencyDemoApplication.class, args);
    }
}
