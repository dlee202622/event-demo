package com.example.demo.jvm;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * JVM 演示模块启动类。
 *
 * <p>本模块有两类运行方式：这个类提供常驻的观测接口（默认 9005）；
 * 另有一批带 {@code main} 的实验类（{@code stack} / {@code oom} / {@code gc} / {@code lifecycle} / {@code leak} 包），
 * 必须用各自的 JVM 参数单独起进程才有意义，不要试图通过本进程调用它们。</p>
 *
 * <p>启动建议带上 {@code -XX:NativeMemoryTracking=summary}（spring-boot 插件已默认加上），
 * 否则「虚拟机栈」和「本地内存」两个区读不到数据。</p>
 */
@SpringBootApplication
public class JvmDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(JvmDemoApplication.class, args);
    }
}
