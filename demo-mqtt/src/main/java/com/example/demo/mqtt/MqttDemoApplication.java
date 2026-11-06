package com.example.demo.mqtt;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MQTT 教学演示应用入口。
 *
 * <p>本模块通过 EMQX Broker 演示 MQTT 协议的核心特性：
 * <ul>
 *   <li>场景 1：基础发布/订阅 — 观察消息从 publisher 到 subscriber 的流转</li>
 *   <li>场景 2：QoS 三级对比 — 观察不同 QoS 下的确认机制与消息可靠性差异</li>
 *   <li>场景 3：通配符订阅 — 观察 {@code +} 单层通配与 {@code #} 多层通配的匹配规则</li>
 * </ul>
 *
 * <p>依赖：启动前需先运行 {@code docker compose up -d emqx}，确保 Broker 可用。
 */
@SpringBootApplication
public class MqttDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(MqttDemoApplication.class, args);
    }
}
