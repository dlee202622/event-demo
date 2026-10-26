# 中间件接入 Demo 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 DDD demo 项目中新增 RabbitMQ 和 RocketMQ 两个独立子模块，展示各中间件的核心特性，通过 REST 接口触发和验证。

**Architecture:** 根 pom 重构为多模块 parent，现有代码迁入 demo-core 子模块。新增 demo-rabbitmq（端口 8081）和 demo-rocketmq（端口 8082）两个独立 Spring Boot 应用，各自提供 REST Controller 触发各类消息模式。Docker Compose 提供中间件基础设施。

**Tech Stack:** Spring Boot 4.1.2, Java 21, Spring AMQP, RocketMQ Spring Boot Starter, Maven 多模块, Docker Compose

**Spec:** `docs/superpowers/specs/2026-10-26-middleware-demo-design.md`

## Global Constraints

- Java 版本：21
- Spring Boot 版本：4.1.2
- 不修改现有 demo-core 中的任何业务代码
- 每个子模块独立端口：demo-core=8080, rabbitmq=8081, rocketmq=8082
- 所有 REST 接口返回简单 JSON 响应，方便 curl/HTTP 客户端测试

---

### Task 1: 多模块 Maven 重构

**Files:**
- Modify: `pom.xml` (根 pom → parent/aggregator)
- Create: `demo-core/pom.xml`
- Move: `src/` → `demo-core/src/`

**Interfaces:**
- Consumes: 现有根 pom.xml 及 src/ 目录
- Produces: 多模块 Maven 项目骨架，`mvn compile` 通过

- [ ] **Step 1: 创建 demo-core 目录并移动现有代码**

```bash
mkdir -p demo-core
git mv src demo-core/src
git mv HELP.md demo-core/HELP.md
git mv mvnw demo-core/mvnw
git mv mvnw.cmd demo-core/mvnw.cmd
```

- [ ] **Step 2: 创建 demo-core/pom.xml**

创建 `demo-core/pom.xml`，内容与原根 pom 基本一致，但添加 parent 引用并移除 build 配置中的 spring-boot-maven-plugin（由 parent 管理）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>demo</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <artifactId>demo-core</artifactId>
    <name>demo-core</name>
    <description>DDD event-driven demo core</description>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 3: 改写根 pom.xml 为 parent/aggregator**

将根 `pom.xml` 替换为：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.2</version>
        <relativePath/>
    </parent>
    <groupId>com.example</groupId>
    <artifactId>demo</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>demo</name>
    <description>demo</description>

    <properties>
        <java.version>21</java.version>
    </properties>

    <modules>
        <module>demo-core</module>
        <module>demo-rabbitmq</module>
        <module>demo-rocketmq</module>
    </modules>
</project>
```

- [ ] **Step 4: 验证编译通过**

```bash
mvn compile -pl demo-core
```

Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add -A
git commit -m "refactor: restructure to multi-module Maven project"
```

---

### Task 2: Docker Compose 基础设施

**Files:**
- Create: `docker-compose.yml`

**Interfaces:**
- Consumes: 无
- Produces: `docker-compose.yml`，`docker compose up -d` 可启动 RabbitMQ 和 RocketMQ

- [ ] **Step 1: 创建 docker-compose.yml**

在项目根目录创建 `docker-compose.yml`：

```yaml
services:
  rabbitmq:
    image: rabbitmq:3-management
    ports:
      - "5672:5672"
      - "15672:15672"

  rocketmq-namesrv:
    image: apache/rocketmq:5.3.1
    command: sh mqnamesrv
    ports:
      - "9876:9876"

  rocketmq-broker:
    image: apache/rocketmq:5.3.1
    command: sh mqbroker -n rocketmq-namesrv:9876 --enable-proxy
    depends_on:
      - rocketmq-namesrv
    environment:
      NAMESRV_ADDR: rocketmq-namesrv:9876
    ports:
      - "10911:10911"
      - "8091:8081"
```

- [ ] **Step 2: 提交**

```bash
git add docker-compose.yml
git commit -m "infra: add docker-compose for RabbitMQ and RocketMQ"
```

---

### Task 3: RabbitMQ 模块骨架

**Files:**
- Create: `demo-rabbitmq/pom.xml`
- Create: `demo-rabbitmq/src/main/resources/application.yaml`
- Create: `demo-rabbitmq/src/main/java/com/example/demo/rabbitmq/RabbitMQDemoApplication.java`
- Create: `demo-rabbitmq/src/main/java/com/example/demo/rabbitmq/config/RabbitMQConfig.java`
- Create: `demo-rabbitmq/src/main/java/com/example/demo/rabbitmq/controller/RabbitMQDemoController.java`

**Interfaces:**
- Consumes: 根 pom parent
- Produces: 可启动的 Spring Boot 应用骨架，包含所有队列/交换机/绑定的声明，REST Controller 骨架

- [ ] **Step 1: 创建 demo-rabbitmq/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>demo</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <artifactId>demo-rabbitmq</artifactId>
    <name>demo-rabbitmq</name>
    <description>RabbitMQ integration demo</description>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 创建 application.yaml**

创建 `demo-rabbitmq/src/main/resources/application.yaml`：

```yaml
spring:
  application:
    name: demo-rabbitmq
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest
    publisher-confirm-type: correlated
    publisher-returns: true

server:
  port: 8081
```

- [ ] **Step 3: 创建 RabbitMQDemoApplication.java**

```java
package com.example.demo.rabbitmq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RabbitMQDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(RabbitMQDemoApplication.class, args);
    }
}
```

- [ ] **Step 4: 创建 RabbitMQConfig.java — 声明所有队列、交换机、绑定**

```java
package com.example.demo.rabbitmq.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String SIMPLE_QUEUE = "demo.simple.queue";

    public static final String WORK_QUEUE = "demo.work.queue";

    public static final String FANOUT_EXCHANGE = "demo.fanout.exchange";
    public static final String FANOUT_QUEUE_A = "demo.fanout.queue.a";
    public static final String FANOUT_QUEUE_B = "demo.fanout.queue.b";

    public static final String DIRECT_EXCHANGE = "demo.direct.exchange";
    public static final String DIRECT_QUEUE_ORDER = "demo.direct.queue.order";
    public static final String DIRECT_QUEUE_PAYMENT = "demo.direct.queue.payment";
    public static final String DIRECT_KEY_ORDER = "order.created";
    public static final String DIRECT_KEY_PAYMENT = "order.paid";

    public static final String TOPIC_EXCHANGE = "demo.topic.exchange";
    public static final String TOPIC_QUEUE_ALL_ORDER = "demo.topic.queue.all-order";
    public static final String TOPIC_QUEUE_CREATED = "demo.topic.queue.created";

    public static final String DLX_EXCHANGE = "demo.dlx.exchange";
    public static final String DLX_QUEUE = "demo.dlx.queue";
    public static final String DLQ_MAIN_QUEUE = "demo.dlq.main.queue";

    @Bean
    public Queue simpleQueue() {
        return new Queue(SIMPLE_QUEUE);
    }

    @Bean
    public Queue workQueue() {
        return new Queue(WORK_QUEUE);
    }

    // ── Fanout ──

    @Bean
    public FanoutExchange fanoutExchange() {
        return new FanoutExchange(FANOUT_EXCHANGE);
    }

    @Bean
    public Queue fanoutQueueA() {
        return new Queue(FANOUT_QUEUE_A);
    }

    @Bean
    public Queue fanoutQueueB() {
        return new Queue(FANOUT_QUEUE_B);
    }

    @Bean
    public Binding fanoutBindingA() {
        return BindingBuilder.bind(fanoutQueueA()).to(fanoutExchange());
    }

    @Bean
    public Binding fanoutBindingB() {
        return BindingBuilder.bind(fanoutQueueB()).to(fanoutExchange());
    }

    // ── Direct ──

    @Bean
    public DirectExchange directExchange() {
        return new DirectExchange(DIRECT_EXCHANGE);
    }

    @Bean
    public Queue directQueueOrder() {
        return new Queue(DIRECT_QUEUE_ORDER);
    }

    @Bean
    public Queue directQueuePayment() {
        return new Queue(DIRECT_QUEUE_PAYMENT);
    }

    @Bean
    public Binding directBindingOrder() {
        return BindingBuilder.bind(directQueueOrder()).to(directExchange()).with(DIRECT_KEY_ORDER);
    }

    @Bean
    public Binding directBindingPayment() {
        return BindingBuilder.bind(directQueuePayment()).to(directExchange()).with(DIRECT_KEY_PAYMENT);
    }

    // ── Topic ──

    @Bean
    public TopicExchange topicExchange() {
        return new TopicExchange(TOPIC_EXCHANGE);
    }

    @Bean
    public Queue topicQueueAllOrder() {
        return new Queue(TOPIC_QUEUE_ALL_ORDER);
    }

    @Bean
    public Queue topicQueueCreated() {
        return new Queue(TOPIC_QUEUE_CREATED);
    }

    @Bean
    public Binding topicBindingAllOrder() {
        return BindingBuilder.bind(topicQueueAllOrder()).to(topicExchange()).with("order.*");
    }

    @Bean
    public Binding topicBindingCreated() {
        return BindingBuilder.bind(topicQueueCreated()).to(topicExchange()).with("#.created");
    }

    // ── Dead Letter ──

    @Bean
    public Queue dlxMainQueue() {
        return QueueBuilder.durable(DLQ_MAIN_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-message-ttl", 3000)
                .build();
    }

    @Bean
    public org.springframework.amqp.core.DirectExchange dlxExchange() {
        return new org.springframework.amqp.core.DirectExchange(DLX_EXCHANGE);
    }

    @Bean
    public Queue dlxQueue() {
        return new Queue(DLX_QUEUE);
    }

    @Bean
    public Binding dlxBinding() {
        return BindingBuilder.bind(dlxQueue()).to(dlxExchange()).with("dlq");
    }
}
```

- [ ] **Step 5: 创建 RabbitMQDemoController.java 骨架**

```java
package com.example.demo.rabbitmq.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rabbitmq")
public class RabbitMQDemoController {
}
```

- [ ] **Step 6: 验证编译通过**

```bash
mvn compile -pl demo-rabbitmq
```

Expected: BUILD SUCCESS

- [ ] **Step 7: 提交**

```bash
git add demo-rabbitmq/
git commit -m "feat(rabbitmq): add module skeleton with config and queue declarations"
```

---

### Task 4: RabbitMQ — 简单队列 + 工作队列

**Files:**
- Create: `demo-rabbitmq/src/main/java/com/example/demo/rabbitmq/producer/SimpleProducer.java`
- Create: `demo-rabbitmq/src/main/java/com/example/demo/rabbitmq/consumer/SimpleConsumer.java`
- Create: `demo-rabbitmq/src/main/java/com/example/demo/rabbitmq/producer/WorkProducer.java`
- Create: `demo-rabbitmq/src/main/java/com/example/demo/rabbitmq/consumer/WorkConsumer.java`
- Modify: `demo-rabbitmq/src/main/java/com/example/demo/rabbitmq/controller/RabbitMQDemoController.java`

**Interfaces:**
- Consumes: `RabbitMQConfig.SIMPLE_QUEUE`, `RabbitMQConfig.WORK_QUEUE`
- Produces: POST `/api/rabbitmq/simple`, POST `/api/rabbitmq/work`

- [ ] **Step 1: 创建 SimpleProducer.java**

```java
package com.example.demo.rabbitmq.producer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class SimpleProducer {

    private final RabbitTemplate rabbitTemplate;

    public SimpleProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void send(String message) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.SIMPLE_QUEUE, message);
    }
}
```

- [ ] **Step 2: 创建 SimpleConsumer.java**

```java
package com.example.demo.rabbitmq.consumer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class SimpleConsumer {

    private static final Logger log = LoggerFactory.getLogger(SimpleConsumer.class);

    @RabbitListener(queues = RabbitMQConfig.SIMPLE_QUEUE)
    public void receive(String message) {
        log.info("[SimpleConsumer] received: {}", message);
    }
}
```

- [ ] **Step 3: 创建 WorkProducer.java**

```java
package com.example.demo.rabbitmq.producer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class WorkProducer {

    private final RabbitTemplate rabbitTemplate;

    public WorkProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void send(String message) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.WORK_QUEUE, message);
    }
}
```

- [ ] **Step 4: 创建 WorkConsumer.java — 两个消费者实例模拟竞争**

```java
package com.example.demo.rabbitmq.consumer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class WorkConsumer {

    private static final Logger log = LoggerFactory.getLogger(WorkConsumer.class);

    @RabbitListener(queues = RabbitMQConfig.WORK_QUEUE, concurrency = "2")
    public void receive(String message) {
        log.info("[WorkConsumer] thread={} received: {}", Thread.currentThread().getName(), message);
    }
}
```

- [ ] **Step 5: 在 Controller 中添加端点**

在 `RabbitMQDemoController` 中添加：

```java
package com.example.demo.rabbitmq.controller;

import com.example.demo.rabbitmq.producer.SimpleProducer;
import com.example.demo.rabbitmq.producer.WorkProducer;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/rabbitmq")
public class RabbitMQDemoController {

    private final SimpleProducer simpleProducer;
    private final WorkProducer workProducer;

    public RabbitMQDemoController(SimpleProducer simpleProducer, WorkProducer workProducer) {
        this.simpleProducer = simpleProducer;
        this.workProducer = workProducer;
    }

    @PostMapping("/simple")
    public Map<String, String> sendSimple(@RequestParam(defaultValue = "hello rabbitmq") String message) {
        simpleProducer.send(message);
        return Map.of("status", "sent", "queue", "demo.simple.queue", "message", message);
    }

    @PostMapping("/work")
    public Map<String, String> sendWork(@RequestParam(defaultValue = "work task") String message) {
        workProducer.send(message);
        return Map.of("status", "sent", "queue", "demo.work.queue", "message", message);
    }
}
```

- [ ] **Step 6: 验证编译通过**

```bash
mvn compile -pl demo-rabbitmq
```

Expected: BUILD SUCCESS

- [ ] **Step 7: 提交**

```bash
git add demo-rabbitmq/src/main/java/com/example/demo/rabbitmq/
git commit -m "feat(rabbitmq): add simple queue and work queue demo"
```

---

### Task 5: RabbitMQ — Fanout + Direct + Topic 交换机

**Files:**
- Create: `demo-rabbitmq/src/main/java/com/example/demo/rabbitmq/producer/FanoutProducer.java`
- Create: `demo-rabbitmq/src/main/java/com/example/demo/rabbitmq/consumer/FanoutConsumer.java`
- Create: `demo-rabbitmq/src/main/java/com/example/demo/rabbitmq/producer/DirectProducer.java`
- Create: `demo-rabbitmq/src/main/java/com/example/demo/rabbitmq/consumer/DirectConsumer.java`
- Create: `demo-rabbitmq/src/main/java/com/example/demo/rabbitmq/producer/TopicProducer.java`
- Create: `demo-rabbitmq/src/main/java/com/example/demo/rabbitmq/consumer/TopicConsumer.java`
- Modify: `demo-rabbitmq/src/main/java/com/example/demo/rabbitmq/controller/RabbitMQDemoController.java`

**Interfaces:**
- Consumes: `RabbitMQConfig` 中的 fanout/direct/topic 常量
- Produces: POST `/api/rabbitmq/fanout`, `/api/rabbitmq/direct`, `/api/rabbitmq/topic`

- [ ] **Step 1: 创建 FanoutProducer.java**

```java
package com.example.demo.rabbitmq.producer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class FanoutProducer {

    private final RabbitTemplate rabbitTemplate;

    public FanoutProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void send(String message) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.FANOUT_EXCHANGE, "", message);
    }
}
```

- [ ] **Step 2: 创建 FanoutConsumer.java**

```java
package com.example.demo.rabbitmq.consumer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class FanoutConsumer {

    private static final Logger log = LoggerFactory.getLogger(FanoutConsumer.class);

    @RabbitListener(queues = RabbitMQConfig.FANOUT_QUEUE_A)
    public void receiveA(String message) {
        log.info("[FanoutConsumer-A] received: {}", message);
    }

    @RabbitListener(queues = RabbitMQConfig.FANOUT_QUEUE_B)
    public void receiveB(String message) {
        log.info("[FanoutConsumer-B] received: {}", message);
    }
}
```

- [ ] **Step 3: 创建 DirectProducer.java**

```java
package com.example.demo.rabbitmq.producer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class DirectProducer {

    private final RabbitTemplate rabbitTemplate;

    public DirectProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void send(String routingKey, String message) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.DIRECT_EXCHANGE, routingKey, message);
    }
}
```

- [ ] **Step 4: 创建 DirectConsumer.java**

```java
package com.example.demo.rabbitmq.consumer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class DirectConsumer {

    private static final Logger log = LoggerFactory.getLogger(DirectConsumer.class);

    @RabbitListener(queues = RabbitMQConfig.DIRECT_QUEUE_ORDER)
    public void receiveOrder(String message) {
        log.info("[DirectConsumer-order] received: {}", message);
    }

    @RabbitListener(queues = RabbitMQConfig.DIRECT_QUEUE_PAYMENT)
    public void receivePayment(String message) {
        log.info("[DirectConsumer-payment] received: {}", message);
    }
}
```

- [ ] **Step 5: 创建 TopicProducer.java**

```java
package com.example.demo.rabbitmq.producer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class TopicProducer {

    private final RabbitTemplate rabbitTemplate;

    public TopicProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public void send(String routingKey, String message) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.TOPIC_EXCHANGE, routingKey, message);
    }
}
```

- [ ] **Step 6: 创建 TopicConsumer.java**

```java
package com.example.demo.rabbitmq.consumer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class TopicConsumer {

    private static final Logger log = LoggerFactory.getLogger(TopicConsumer.class);

    @RabbitListener(queues = RabbitMQConfig.TOPIC_QUEUE_ALL_ORDER)
    public void receiveAllOrder(String message) {
        log.info("[TopicConsumer-all-order] received: {}", message);
    }

    @RabbitListener(queues = RabbitMQConfig.TOPIC_QUEUE_CREATED)
    public void receiveCreated(String message) {
        log.info("[TopicConsumer-created] received: {}", message);
    }
}
```

- [ ] **Step 7: 在 Controller 中添加三个端点**

更新 `RabbitMQDemoController.java`，添加 FanoutProducer、DirectProducer、TopicProducer 注入和三个新端点：

```java
@PostMapping("/fanout")
public Map<String, String> sendFanout(@RequestParam(defaultValue = "broadcast msg") String message) {
    fanoutProducer.send(message);
    return Map.of("status", "sent", "exchange", "demo.fanout.exchange", "message", message);
}

@PostMapping("/direct")
public Map<String, String> sendDirect(
        @RequestParam(defaultValue = "order.created") String routingKey,
        @RequestParam(defaultValue = "direct msg") String message) {
    directProducer.send(routingKey, message);
    return Map.of("status", "sent", "exchange", "demo.direct.exchange", "routingKey", routingKey, "message", message);
}

@PostMapping("/topic")
public Map<String, String> sendTopic(
        @RequestParam(defaultValue = "order.created") String routingKey,
        @RequestParam(defaultValue = "topic msg") String message) {
    topicProducer.send(routingKey, message);
    return Map.of("status", "sent", "exchange", "demo.topic.exchange", "routingKey", routingKey, "message", message);
}
```

- [ ] **Step 8: 验证编译通过**

```bash
mvn compile -pl demo-rabbitmq
```

Expected: BUILD SUCCESS

- [ ] **Step 9: 提交**

```bash
git add demo-rabbitmq/src/main/java/com/example/demo/rabbitmq/
git commit -m "feat(rabbitmq): add fanout, direct, and topic exchange demos"
```

---

### Task 6: RabbitMQ — 消息确认 + 死信队列

**Files:**
- Create: `demo-rabbitmq/src/main/java/com/example/demo/rabbitmq/producer/ConfirmProducer.java`
- Create: `demo-rabbitmq/src/main/java/com/example/demo/rabbitmq/consumer/DeadLetterConsumer.java`
- Modify: `demo-rabbitmq/src/main/java/com/example/demo/rabbitmq/controller/RabbitMQDemoController.java`

**Interfaces:**
- Consumes: `RabbitMQConfig.DLQ_MAIN_QUEUE`, RabbitTemplate (with confirm callback)
- Produces: POST `/api/rabbitmq/confirm`, POST `/api/rabbitmq/dlx`

- [ ] **Step 1: 创建 ConfirmProducer.java — 注册 publisher confirm 回调**

```java
package com.example.demo.rabbitmq.producer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class ConfirmProducer {

    private static final Logger log = LoggerFactory.getLogger(ConfirmProducer.class);

    private final RabbitTemplate rabbitTemplate;

    public ConfirmProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @PostConstruct
    public void init() {
        rabbitTemplate.setConfirmCallback((correlationData, ack, cause) -> {
            if (ack) {
                log.info("[ConfirmProducer] message confirmed by broker");
            } else {
                log.warn("[ConfirmProducer] message NOT confirmed, cause: {}", cause);
            }
        });
        rabbitTemplate.setReturnsCallback(returned -> {
            log.warn("[ConfirmProducer] message returned: exchange={}, routingKey={}, replyCode={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode());
        });
    }

    public void send(String message) {
        rabbitTemplate.convertAndSend(RabbitMQConfig.SIMPLE_QUEUE, message);
    }

    public void sendUnroutable(String message) {
        rabbitTemplate.convertAndSend("non-existent-exchange", "no-key", message);
    }
}
```

- [ ] **Step 2: 创建 DeadLetterConsumer.java**

```java
package com.example.demo.rabbitmq.consumer;

import com.example.demo.rabbitmq.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.stereotype.Component;

@Component
public class DeadLetterConsumer {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterConsumer.class);

    @RabbitListener(queues = RabbitMQConfig.DLQ_MAIN_QUEUE)
    public void receiveMain(String message) {
        log.info("[DeadLetterConsumer-main] received: {}, will reject to DLQ", message);
        throw new AmqpRejectAndDontRequeueException("reject to DLQ demo");
    }

    @RabbitListener(queues = RabbitMQConfig.DLX_QUEUE)
    public void receiveDlx(String message) {
        log.info("[DeadLetterConsumer-DLQ] received dead letter: {}", message);
    }
}
```

- [ ] **Step 3: 在 Controller 中添加两个端点**

更新 `RabbitMQDemoController.java`，添加 ConfirmProducer 注入和端点：

```java
@PostMapping("/confirm")
public Map<String, String> sendConfirm(@RequestParam(defaultValue = "confirm msg") String message) {
    confirmProducer.send(message);
    return Map.of("status", "sent", "feature", "publisher-confirm", "message", message);
}

@PostMapping("/dlx")
public Map<String, String> sendDlx(@RequestParam(defaultValue = "dlx msg") String message) {
    confirmProducer.send(RabbitMQConfig.DLQ_MAIN_QUEUE, message);
    return Map.of("status", "sent", "feature", "dead-letter", "hint", "message will be rejected and routed to DLQ after 3s TTL", "message", message);
}
```

注意：需要在 ConfirmProducer 中添加一个重载方法 `send(String queue, String message)` 用于指定队列发送：

```java
public void send(String queue, String message) {
    rabbitTemplate.convertAndSend(queue, message);
}
```

- [ ] **Step 4: 验证编译通过**

```bash
mvn compile -pl demo-rabbitmq
```

Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add demo-rabbitmq/src/main/java/com/example/demo/rabbitmq/
git commit -m "feat(rabbitmq): add publisher confirm and dead letter queue demo"
```

---

### Task 7: RocketMQ 模块骨架

**Files:**
- Create: `demo-rocketmq/pom.xml`
- Create: `demo-rocketmq/src/main/resources/application.yaml`
- Create: `demo-rocketmq/src/main/java/com/example/demo/rocketmq/RocketMQDemoApplication.java`
- Create: `demo-rocketmq/src/main/java/com/example/demo/rocketmq/controller/RocketMQDemoController.java`

**Interfaces:**
- Consumes: 根 pom parent
- Produces: 可启动的 Spring Boot 应用骨架，REST Controller 骨架

- [ ] **Step 1: 创建 demo-rocketmq/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>demo</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <artifactId>demo-rocketmq</artifactId>
    <name>demo-rocketmq</name>
    <description>RocketMQ integration demo</description>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>org.apache.rocketmq</groupId>
            <artifactId>rocketmq-spring-boot-starter</artifactId>
            <version>2.3.1</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: 创建 application.yaml**

创建 `demo-rocketmq/src/main/resources/application.yaml`：

```yaml
spring:
  application:
    name: demo-rocketmq

rocketmq:
  name-server: localhost:9876
  producer:
    group: demo-producer-group
    send-message-timeout: 3000

server:
  port: 8082
```

- [ ] **Step 3: 创建 RocketMQDemoApplication.java**

```java
package com.example.demo.rocketmq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RocketMQDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(RocketMQDemoApplication.class, args);
    }
}
```

- [ ] **Step 4: 创建 RocketMQDemoController.java 骨架**

```java
package com.example.demo.rocketmq.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rocketmq")
public class RocketMQDemoController {
}
```

- [ ] **Step 5: 验证编译通过**

```bash
mvn compile -pl demo-rocketmq
```

Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add demo-rocketmq/
git commit -m "feat(rocketmq): add module skeleton"
```

---

### Task 8: RocketMQ — 基础收发 + 顺序消息

**Files:**
- Create: `demo-rocketmq/src/main/java/com/example/demo/rocketmq/producer/SimpleProducer.java`
- Create: `demo-rocketmq/src/main/java/com/example/demo/rocketmq/consumer/SimpleConsumer.java`
- Create: `demo-rocketmq/src/main/java/com/example/demo/rocketmq/producer/OrderlyProducer.java`
- Create: `demo-rocketmq/src/main/java/com/example/demo/rocketmq/consumer/OrderlyConsumer.java`
- Modify: `demo-rocketmq/src/main/java/com/example/demo/rocketmq/controller/RocketMQDemoController.java`

**Interfaces:**
- Consumes: `RocketMQTemplate`
- Produces: POST `/api/rocketmq/simple`, POST `/api/rocketmq/orderly`

- [ ] **Step 1: 创建 SimpleProducer.java**

```java
package com.example.demo.rocketmq.producer;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

@Component
public class SimpleProducer {

    private final RocketMQTemplate rocketTemplate;

    public SimpleProducer(RocketMQTemplate rocketTemplate) {
        this.rocketTemplate = rocketTemplate;
    }

    public void send(String message) {
        rocketTemplate.convertAndSend("demo-simple-topic", message);
    }
}
```

- [ ] **Step 2: 创建 SimpleConsumer.java**

```java
package com.example.demo.rocketmq.consumer;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
        topic = "demo-simple-topic",
        consumerGroup = "demo-simple-consumer-group"
)
public class SimpleConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(SimpleConsumer.class);

    @Override
    public void onMessage(String message) {
        log.info("[SimpleConsumer] received: {}", message);
    }
}
```

- [ ] **Step 3: 创建 OrderlyProducer.java**

```java
package com.example.demo.rocketmq.producer;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderlyProducer {

    private final RocketMQTemplate rocketTemplate;

    public OrderlyProducer(RocketMQTemplate rocketTemplate) {
        this.rocketTemplate = rocketTemplate;
    }

    public void send(String orderKey, String message) {
        rocketTemplate.syncSendOrderly("demo-orderly-topic", message, orderKey);
    }
}
```

- [ ] **Step 4: 创建 OrderlyConsumer.java**

```java
package com.example.demo.rocketmq.consumer;

import org.apache.rocketmq.spring.annotation.ConsumeMode;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
        topic = "demo-orderly-topic",
        consumerGroup = "demo-orderly-consumer-group",
        consumeMode = ConsumeMode.ORDERLY
)
public class OrderlyConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(OrderlyConsumer.class);

    @Override
    public void onMessage(String message) {
        log.info("[OrderlyConsumer] thread={} received: {}", Thread.currentThread().getName(), message);
    }
}
```

- [ ] **Step 5: 在 Controller 中添加端点**

更新 `RocketMQDemoController.java`：

```java
package com.example.demo.rocketmq.controller;

import com.example.demo.rocketmq.producer.OrderlyProducer;
import com.example.demo.rocketmq.producer.SimpleProducer;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/rocketmq")
public class RocketMQDemoController {

    private final SimpleProducer simpleProducer;
    private final OrderlyProducer orderlyProducer;

    public RocketMQDemoController(SimpleProducer simpleProducer, OrderlyProducer orderlyProducer) {
        this.simpleProducer = simpleProducer;
        this.orderlyProducer = orderlyProducer;
    }

    @PostMapping("/simple")
    public Map<String, String> sendSimple(@RequestParam(defaultValue = "hello rocketmq") String message) {
        simpleProducer.send(message);
        return Map.of("status", "sent", "topic", "demo-simple-topic", "message", message);
    }

    @PostMapping("/orderly")
    public Map<String, String> sendOrderly(
            @RequestParam(defaultValue = "order-001") String orderKey,
            @RequestParam(defaultValue = "orderly msg") String message) {
        orderlyProducer.send(orderKey, message);
        return Map.of("status", "sent", "topic", "demo-orderly-topic", "orderKey", orderKey, "message", message);
    }
}
```

- [ ] **Step 6: 验证编译通过**

```bash
mvn compile -pl demo-rocketmq
```

Expected: BUILD SUCCESS

- [ ] **Step 7: 提交**

```bash
git add demo-rocketmq/src/main/java/com/example/demo/rocketmq/
git commit -m "feat(rocketmq): add simple and orderly message demo"
```

---

### Task 9: RocketMQ — 标签过滤 + 延迟消息

**Files:**
- Create: `demo-rocketmq/src/main/java/com/example/demo/rocketmq/producer/TagProducer.java`
- Create: `demo-rocketmq/src/main/java/com/example/demo/rocketmq/consumer/TagFilterConsumer.java`
- Create: `demo-rocketmq/src/main/java/com/example/demo/rocketmq/producer/DelayProducer.java`
- Create: `demo-rocketmq/src/main/java/com/example/demo/rocketmq/consumer/DelayConsumer.java`
- Modify: `demo-rocketmq/src/main/java/com/example/demo/rocketmq/controller/RocketMQDemoController.java`

**Interfaces:**
- Consumes: `RocketMQTemplate`
- Produces: POST `/api/rocketmq/tag`, POST `/api/rocketmq/delay`

- [ ] **Step 1: 创建 TagProducer.java**

```java
package com.example.demo.rocketmq.producer;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
public class TagProducer {

    private final RocketMQTemplate rocketTemplate;

    public TagProducer(RocketMQTemplate rocketTemplate) {
        this.rocketTemplate = rocketTemplate;
    }

    public void send(String tag, String message) {
        rocketTemplate.send("demo-tag-topic:" + tag, MessageBuilder.withPayload(message).build());
    }
}
```

- [ ] **Step 2: 创建 TagFilterConsumer.java**

```java
package com.example.demo.rocketmq.consumer;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
        topic = "demo-tag-topic",
        consumerGroup = "demo-tag-consumer-group",
        selectorExpression = "TagA"
)
public class TagFilterConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(TagFilterConsumer.class);

    @Override
    public void onMessage(String message) {
        log.info("[TagFilterConsumer] received (TagA only): {}", message);
    }
}
```

- [ ] **Step 3: 创建 DelayProducer.java**

```java
package com.example.demo.rocketmq.producer;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
public class DelayProducer {

    private final RocketMQTemplate rocketTemplate;

    public DelayProducer(RocketMQTemplate rocketTemplate) {
        this.rocketTemplate = rocketTemplate;
    }

    public void send(String message, int delayLevel) {
        rocketTemplate.syncSend("demo-delay-topic",
                MessageBuilder.withPayload(message).build(),
                3000, delayLevel);
    }
}
```

- [ ] **Step 4: 创建 DelayConsumer.java**

```java
package com.example.demo.rocketmq.consumer;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
        topic = "demo-delay-topic",
        consumerGroup = "demo-delay-consumer-group"
)
public class DelayConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(DelayConsumer.class);

    @Override
    public void onMessage(String message) {
        log.info("[DelayConsumer] received at {}: {}", System.currentTimeMillis(), message);
    }
}
```

- [ ] **Step 5: 在 Controller 中添加端点**

在 `RocketMQDemoController` 中添加 TagProducer 和 DelayProducer 注入及端点：

```java
@PostMapping("/tag")
public Map<String, String> sendTag(
        @RequestParam(defaultValue = "TagA") String tag,
        @RequestParam(defaultValue = "tag msg") String message) {
    tagProducer.send(tag, message);
    return Map.of("status", "sent", "topic", "demo-tag-topic", "tag", tag, "message", message);
}

@PostMapping("/delay")
public Map<String, String> sendDelay(
        @RequestParam(defaultValue = "delay msg") String message,
        @RequestParam(defaultValue = "3") int delayLevel) {
    delayProducer.send(message, delayLevel);
    return Map.of("status", "sent", "topic", "demo-delay-topic", "delayLevel", String.valueOf(delayLevel), "message", message);
}
```

- [ ] **Step 6: 验证编译通过**

```bash
mvn compile -pl demo-rocketmq
```

Expected: BUILD SUCCESS

- [ ] **Step 7: 提交**

```bash
git add demo-rocketmq/src/main/java/com/example/demo/rocketmq/
git commit -m "feat(rocketmq): add tag filter and delay message demo"
```

---

### Task 10: RocketMQ — 事务消息

**Files:**
- Create: `demo-rocketmq/src/main/java/com/example/demo/rocketmq/producer/TransactionProducer.java`
- Create: `demo-rocketmq/src/main/java/com/example/demo/rocketmq/listener/TransactionListenerImpl.java`
- Create: `demo-rocketmq/src/main/java/com/example/demo/rocketmq/consumer/TransactionConsumer.java`
- Modify: `demo-rocketmq/src/main/java/com/example/demo/rocketmq/controller/RocketMQDemoController.java`

**Interfaces:**
- Consumes: `RocketMQTemplate`, `TransactionListenerImpl`
- Produces: POST `/api/rocketmq/transaction`

- [ ] **Step 1: 创建 TransactionListenerImpl.java**

```java
package com.example.demo.rocketmq.listener;

import org.apache.rocketmq.spring.annotation.RocketMQTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionListener;
import org.apache.rocketmq.spring.core.RocketMQLocalTransactionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;

@RocketMQTransactionListener
public class TransactionListenerImpl implements RocketMQLocalTransactionListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionListenerImpl.class);

    @Override
    public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        log.info("[TransactionListener] executing local transaction for: {}", msg.getPayload());
        try {
            log.info("[TransactionListener] local transaction committed");
            return RocketMQLocalTransactionState.COMMIT;
        } catch (Exception e) {
            log.error("[TransactionListener] local transaction failed", e);
            return RocketMQLocalTransactionState.ROLLBACK;
        }
    }

    @Override
    public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
        log.info("[TransactionListener] checking local transaction for: {}", msg.getPayload());
        return RocketMQLocalTransactionState.COMMIT;
    }
}
```

- [ ] **Step 2: 创建 TransactionProducer.java**

```java
package com.example.demo.rocketmq.producer;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Component
public class TransactionProducer {

    private final RocketMQTemplate rocketTemplate;

    public TransactionProducer(RocketMQTemplate rocketTemplate) {
        this.rocketTemplate = rocketTemplate;
    }

    public void send(String message) {
        rocketTemplate.sendMessageInTransaction("demo-tx-topic",
                MessageBuilder.withPayload(message).build(), null);
    }
}
```

- [ ] **Step 3: 创建 TransactionConsumer.java**

```java
package com.example.demo.rocketmq.consumer;

import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
        topic = "demo-tx-topic",
        consumerGroup = "demo-tx-consumer-group"
)
public class TransactionConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(TransactionConsumer.class);

    @Override
    public void onMessage(String message) {
        log.info("[TransactionConsumer] received transactional message: {}", message);
    }
}
```

- [ ] **Step 4: 在 Controller 中添加端点**

在 `RocketMQDemoController` 中添加 TransactionProducer 注入及端点：

```java
@PostMapping("/transaction")
public Map<String, String> sendTransaction(@RequestParam(defaultValue = "tx msg") String message) {
    transactionProducer.send(message);
    return Map.of("status", "sent", "topic", "demo-tx-topic", "feature", "transaction", "message", message);
}
```

- [ ] **Step 5: 验证编译通过**

```bash
mvn compile -pl demo-rocketmq
```

Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add demo-rocketmq/src/main/java/com/example/demo/rocketmq/
git commit -m "feat(rocketmq): add transaction message demo"
```

---

### Task 11: RocketMQ — 广播消费

**Files:**
- Create: `demo-rocketmq/src/main/java/com/example/demo/rocketmq/producer/BroadcastProducer.java`
- Create: `demo-rocketmq/src/main/java/com/example/demo/rocketmq/consumer/BroadcastConsumer.java`
- Modify: `demo-rocketmq/src/main/java/com/example/demo/rocketmq/controller/RocketMQDemoController.java`

**Interfaces:**
- Consumes: `RocketMQTemplate`
- Produces: POST `/api/rocketmq/broadcast`

- [ ] **Step 1: 创建 BroadcastProducer.java**

```java
package com.example.demo.rocketmq.producer;

import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Component;

@Component
public class BroadcastProducer {

    private final RocketMQTemplate rocketTemplate;

    public BroadcastProducer(RocketMQTemplate rocketTemplate) {
        this.rocketTemplate = rocketTemplate;
    }

    public void send(String message) {
        rocketTemplate.convertAndSend("demo-broadcast-topic", message);
    }
}
```

- [ ] **Step 2: 创建 BroadcastConsumer.java**

```java
package com.example.demo.rocketmq.consumer;

import org.apache.rocketmq.spring.annotation.MessageModel;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
@RocketMQMessageListener(
        topic = "demo-broadcast-topic",
        consumerGroup = "demo-broadcast-consumer-group",
        messageModel = MessageModel.BROADCASTING
)
public class BroadcastConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(BroadcastConsumer.class);

    @Override
    public void onMessage(String message) {
        log.info("[BroadcastConsumer] received: {}", message);
    }
}
```

- [ ] **Step 3: 在 Controller 中添加端点**

在 `RocketMQDemoController` 中添加 BroadcastProducer 注入及端点：

```java
@PostMapping("/broadcast")
public Map<String, String> sendBroadcast(@RequestParam(defaultValue = "broadcast msg") String message) {
    broadcastProducer.send(message);
    return Map.of("status", "sent", "topic", "demo-broadcast-topic", "feature", "broadcast", "message", message);
}
```

- [ ] **Step 4: 验证编译通过**

```bash
mvn compile -pl demo-rocketmq
```

Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add demo-rocketmq/src/main/java/com/example/demo/rocketmq/
git commit -m "feat(rocketmq): add broadcast consumer demo"
```

---

### Task 12: 全模块编译验证

**Files:**
- 无新增文件

**Interfaces:**
- Consumes: 所有模块代码
- Produces: 全项目 `mvn compile` 通过

- [ ] **Step 1: 全模块编译**

```bash
mvn compile
```

Expected: BUILD SUCCESS，三个模块均编译通过

- [ ] **Step 2: 验证 demo-core 仍可独立运行**

```bash
mvn spring-boot:run -pl demo-core
```

Expected: 应用在 8080 端口启动，现有功能不受影响

- [ ] **Step 3: 最终提交**

```bash
git add -A
git commit -m "feat: middleware demo modules complete (RabbitMQ + RocketMQ)"
```
