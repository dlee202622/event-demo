# demo-core 微服务拆分实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 demo-core 单体拆分为 3 个独立微服务（order/inventory/loyalty），通过 RabbitMQ Topic Exchange 通信。

**Architecture:** 每个服务是独立 Spring Boot 应用，依赖 event-driven-spring-boot-starter 获得事件驱动能力。共享基类（AggregateRoot、DomainException）移入 starter。每个服务定义自己的队列（`${spring.application.name}.queue`），通过同一个 Topic Exchange 广播事件。各服务独立定义所消费事件的 record 类。

**Tech Stack:** Spring Boot 4.1.2, Java 21, Maven, RabbitMQ, event-driven-spring-boot-starter

**Spec:** `docs/superpowers/specs/2026-10-27-microservice-split-design.md`

## Global Constraints

- Java 21, Spring Boot 4.1.2
- 端口：order=9001, inventory=9002, loyalty=9003
- RabbitMQ: localhost:5672, guest/guest
- Exchange: `event-driven.exchange` (Topic)
- 每个服务队列名: `${spring.application.name}.queue`
- 各服务独立定义消费的事件 record，不共享 contracts 模块
- AggregateRoot/DomainException 移入 starter 的 `com.example.demo.eventdriven.domain` 包

## File Structure

### Starter 增强（新增/修改）

```
event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/
├── domain/
│   ├── DomainEvent.java              (已有)
│   ├── DomainEventPublisher.java     (已有)
│   ├── AggregateRoot.java            ← 从 demo-core 移入
│   └── DomainException.java          ← 从 demo-core 移入
├── autoconfigure/
│   ├── EventDrivenProperties.java    (修改：queueName 属性已存在，确认默认值)
│   └── EventDrivenAutoConfiguration.java (已有)
└── rabbitmq/
    ├── EventRabbitMQConfig.java      (修改：使用 properties 中的 queueName)
    └── EventMessageListener.java     (修改：使用动态队列名)
```

### demo-order-service

```
demo-order-service/
├── pom.xml
└── src/main/
    ├── java/com/example/demo/
    │   ├── OrderServiceApplication.java
    │   ├── order/
    │   │   ├── application/
    │   │   │   ├── OrderApplicationService.java
    │   │   │   ├── command/PlaceOrderCommand.java
    │   │   │   └── query/OrderQueryService.java, OrderView.java, OrderViewStore.java
    │   │   ├── domain/
    │   │   │   ├── event/OrderCreatedEvent.java, OrderConfirmedEvent.java, OrderPaidEvent.java, OrderCancelledEvent.java
    │   │   │   ├── model/Order.java, OrderId.java, OrderItem.java, Money.java, OrderStatus.java
    │   │   │   └── repository/OrderRepository.java, OrderNotFoundException.java
    │   │   ├── infrastructure/
    │   │   │   ├── persistence/InMemoryOrderRepository.java
    │   │   │   ├── projection/OrderProjectionHandler.java, InMemoryOrderViewStore.java
    │   │   │   └── listener/InventoryFailureCompensationHandler.java, OrderNotificationHandler.java
    │   │   └── interfaces/rest/OrderController.java, GlobalExceptionHandler.java
    │   │       └── dto/EventResponse.java, PlaceOrderRequest.java
    │   ├── admin/DiagnosticsController.java        (简化：移除 inventory/loyalty 依赖)
    │   ├── infrastructure/
    │   │   ├── event/DomainEventStore.java, DomainEventAuditListener.java
    │   │   ├── rabbitmq/OrderServiceEventConsumer.java
    │   │   └── web/WebConfig.java
    │   └── order/domain/event/InventoryReservationFailedEvent.java  (本地定义)
    └── resources/
        ├── application.yaml
        └── static/index.html
```

### demo-inventory-service

```
demo-inventory-service/
├── pom.xml
└── src/main/
    ├── java/com/example/demo/
    │   ├── InventoryServiceApplication.java
    │   ├── inventory/
    │   │   ├── InventoryService.java
    │   │   ├── event/InventoryEventListener.java
    │   │   ├── domain/event/
    │   │   │   ├── InventoryReservationFailedEvent.java
    │   │   │   ├── OrderCreatedEvent.java       (本地定义，含本地 OrderItem)
    │   │   │   ├── OrderCancelledEvent.java     (本地定义，含本地 OrderItem)
    │   │   │   └── OrderItem.java               (本地定义)
    │   │   └── interfaces/InventoryController.java  (可选 REST)
    │   └── infrastructure/
    │       └── rabbitmq/InventoryServiceEventConsumer.java
    └── resources/application.yaml
```

### demo-loyalty-service

```
demo-loyalty-service/
├── pom.xml
└── src/main/
    ├── java/com/example/demo/
    │   ├── LoyaltyServiceApplication.java
    │   ├── loyalty/
    │   │   ├── LoyaltyPointsService.java
    │   │   ├── event/LoyaltyEventListener.java
    │   │   └── domain/event/
    │   │       └── OrderPaidEvent.java          (本地定义，含本地 Money)
    │   │       └── Money.java                   (本地定义)
    │   └── infrastructure/
    │       └── rabbitmq/LoyaltyServiceEventConsumer.java
    └── resources/application.yaml
```

---

### Task 1: 增强 Starter — 移入共享基类 + 队列名可配置

**Files:**
- Create: `event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/domain/AggregateRoot.java`
- Create: `event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/domain/DomainException.java`
- Modify: `event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/rabbitmq/EventRabbitMQConfig.java`
- Modify: `event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/rabbitmq/EventMessageListener.java`
- Modify: `event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/autoconfigure/EventDrivenAutoConfiguration.java`
- Test: `event-driven-spring-boot-starter/src/test/java/com/example/demo/eventdriven/autoconfigure/EventDrivenAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `EventDrivenProperties.RabbitMQ.getQueue()` 返回队列名
- Produces: `AggregateRoot` 在 `com.example.demo.eventdriven.domain` 包，`DomainException` 在同包

- [ ] **Step 1: 创建 AggregateRoot 到 starter**

```java
// event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/domain/AggregateRoot.java
package com.example.demo.eventdriven.domain;

import java.util.ArrayList;
import java.util.List;

public abstract class AggregateRoot {

    private final transient List<DomainEvent> domainEvents = new ArrayList<>();

    protected void registerEvent(DomainEvent event) {
        this.domainEvents.add(event);
    }

    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> events = List.copyOf(this.domainEvents);
        this.domainEvents.clear();
        return events;
    }

    public boolean hasUnpublishedEvents() {
        return !this.domainEvents.isEmpty();
    }

    public List<DomainEvent> peekDomainEvents() {
        return List.copyOf(this.domainEvents);
    }
}
```

- [ ] **Step 2: 创建 DomainException 到 starter**

```java
// event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/domain/DomainException.java
package com.example.demo.eventdriven.domain;

public class DomainException extends RuntimeException {

    public DomainException(String message) {
        super(message);
    }

    public DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 3: 修改 EventRabbitMQConfig 使用动态队列名**

将 `EventRabbitMQConfig` 从硬编码常量改为接收 `EventDrivenProperties`，使用配置的队列名：

```java
// event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/rabbitmq/EventRabbitMQConfig.java
package com.example.demo.eventdriven.rabbitmq;

import com.example.demo.eventdriven.autoconfigure.EventDrivenProperties;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableRabbit
public class EventRabbitMQConfig {

    public static final String EXCHANGE = "event-driven.exchange";
    public static final String ROUTING_KEY_PREFIX = "event";
    public static final String DLX_EXCHANGE = "event-driven.dlx";
    public static final String DLQ_QUEUE = "event-driven.dlq";

    private final EventDrivenProperties properties;

    public EventRabbitMQConfig(EventDrivenProperties properties) {
        this.properties = properties;
    }

    @Bean
    public TopicExchange eventExchange() {
        return new TopicExchange(
                properties.getRabbitmq().getExchange());
    }

    @Bean
    public Queue eventQueue() {
        return QueueBuilder.durable(
                        properties.getRabbitmq().getQueue())
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", DLQ_QUEUE)
                .build();
    }

    @Bean
    public Binding eventBinding() {
        return BindingBuilder.bind(eventQueue())
                .to(eventExchange())
                .with(properties.getRabbitmq().getRoutingKeyPrefix() + ".#");
    }

    @Bean
    public Queue dlqQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    public String getQueueName() {
        return properties.getRabbitmq().getQueue();
    }
}
```

- [ ] **Step 4: 修改 EventMessageListener 使用动态队列名**

`@RabbitListener` 的 queues 属性改为 SpEL 表达式引用配置：

```java
// event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/rabbitmq/EventMessageListener.java
package com.example.demo.eventdriven.rabbitmq;

// ... imports same as before ...

public class EventMessageListener {

    private static final Logger log = LoggerFactory.getLogger(EventMessageListener.class);

    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRegistry processedEventRegistry;
    private final EventConsumer eventConsumer;

    public EventMessageListener(ApplicationEventPublisher eventPublisher,
                                ObjectMapper objectMapper,
                                ProcessedEventRegistry processedEventRegistry,
                                EventConsumer eventConsumer) {
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.processedEventRegistry = processedEventRegistry;
        this.eventConsumer = eventConsumer;
    }

    @RabbitListener(queues = "${eventdriven.rabbitmq.queue:event-driven.queue}")
    public void onMessage(String message) {
        // ... same body as before ...
    }
}
```

- [ ] **Step 5: 更新 OutboxRelay 使用 properties 中的 exchange 和 routingKeyPrefix**

修改 `OutboxRelay` 构造函数，增加 `EventDrivenProperties` 参数，替换硬编码常量引用：

```java
// OutboxRelay 构造函数改为：
public OutboxRelay(OutboxStore outboxStore,
                   RabbitTemplate rabbitTemplate,
                   ObjectMapper objectMapper,
                   EventDrivenProperties properties) {
    this.outboxStore = outboxStore;
    this.rabbitTemplate = rabbitTemplate;
    this.objectMapper = objectMapper;
    this.relayEnabled = properties.getOutbox().isRelayEnabled();
    this.exchange = properties.getRabbitmq().getExchange();
    this.routingKeyPrefix = properties.getRabbitmq().getRoutingKeyPrefix();
}
```

新增两个 private final 字段 `exchange` 和 `routingKeyPrefix`，替换 `flush()` 和 `buildRoutingKey()` 中对 `EventRabbitMQConfig.EXCHANGE` 和 `EventRabbitMQConfig.ROUTING_KEY_PREFIX` 的引用。

同步更新 `EventDrivenAutoConfiguration` 中 `outboxRelay()` bean 的创建：

```java
@Bean
@ConditionalOnMissingBean
public OutboxRelay outboxRelay(OutboxStore outboxStore,
                               RabbitTemplate rabbitTemplate,
                               ObjectMapper objectMapper,
                               EventDrivenProperties properties) {
    return new OutboxRelay(outboxStore, rabbitTemplate, objectMapper, properties);
}
```

同步更新 `OutboxRelayTest` 的构造函数调用，传入 mock 的 `EventDrivenProperties`。

- [ ] **Step 6: 运行 starter 测试验证**

Run: `./mvnw test -pl event-driven-spring-boot-starter`
Expected: 16 tests pass (may need to update OutboxRelayTest for new constructor)

- [ ] **Step 7: 更新 demo-core 中的 import（过渡期）**

将 demo-core 中所有引用 `com.example.demo.shared.domain.AggregateRoot` 改为 `com.example.demo.eventdriven.domain.AggregateRoot`，`DomainException` 同理。

Run: `./mvnw compile -pl demo-core -am`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add event-driven-spring-boot-starter/ demo-core/
git commit -m "feat(starter): add AggregateRoot/DomainException, configurable queue name"
```

---

### Task 2: 创建 demo-order-service

**Files:**
- Create: `demo-order-service/pom.xml`
- Create: `demo-order-service/src/main/java/com/example/demo/OrderServiceApplication.java`
- Create: `demo-order-service/src/main/resources/application.yaml`
- Create: 27 Java files (迁移自 demo-core，更新包名和 import)
- Create: `demo-order-service/src/main/java/com/example/demo/infrastructure/rabbitmq/OrderServiceEventConsumer.java`
- Create: `demo-order-service/src/main/java/com/example/demo/admin/DiagnosticsController.java` (简化版)
- Create: `demo-order-service/src/main/resources/static/index.html`

**Interfaces:**
- Consumes: `event-driven-spring-boot-starter` 所有能力
- Produces: 独立可运行的订单服务，端口 9001

- [ ] **Step 1: 创建 pom.xml**

```xml
<!-- demo-order-service/pom.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>com.example</groupId>
        <artifactId>demo</artifactId>
        <version>0.0.1-SNAPSHOT</version>
    </parent>
    <artifactId>demo-order-service</artifactId>
    <name>demo-order-service</name>
    <description>Order Service - DDD event-driven microservice</description>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-webmvc</artifactId>
        </dependency>
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>event-driven-spring-boot-starter</artifactId>
            <version>${project.version}</version>
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

- [ ] **Step 2: 创建 Application 类**

```java
// demo-order-service/src/main/java/com/example/demo/OrderServiceApplication.java
package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
```

- [ ] **Step 3: 创建 application.yaml**

```yaml
# demo-order-service/src/main/resources/application.yaml
spring:
  application:
    name: demo-order-service
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest

server:
  port: 9001

eventdriven:
  rabbitmq:
    queue: ${spring.application.name}.queue

logging:
  level:
    root: INFO
    com.example.demo: INFO
  pattern:
    console: "%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n"
```

- [ ] **Step 4: 迁移 order 领域模型**

从 demo-core 复制以下文件到 demo-order-service，更新 import：
- `com.example.demo.shared.domain.AggregateRoot` → `com.example.demo.eventdriven.domain.AggregateRoot`
- `com.example.demo.shared.domain.DomainException` → `com.example.demo.eventdriven.domain.DomainException`

复制文件列表：
- `order/domain/model/Order.java` — 更新 AggregateRoot/DomainException import
- `order/domain/model/OrderId.java` — 更新 DomainException import
- `order/domain/model/OrderItem.java` — 更新 DomainException import
- `order/domain/model/Money.java` — 更新 DomainException import
- `order/domain/model/OrderStatus.java` — 无变化
- `order/domain/event/OrderCreatedEvent.java` — 无变化
- `order/domain/event/OrderConfirmedEvent.java` — 无变化
- `order/domain/event/OrderPaidEvent.java` — 无变化
- `order/domain/event/OrderCancelledEvent.java` — 无变化
- `order/domain/repository/OrderRepository.java` — 无变化
- `order/domain/repository/OrderNotFoundException.java` — 更新 DomainException import

- [ ] **Step 5: 迁移 order 应用层和基础设施层**

复制文件列表（无 import 变化或仅更新 DomainException）：
- `order/application/OrderApplicationService.java`
- `order/application/command/PlaceOrderCommand.java`
- `order/application/query/OrderQueryService.java`
- `order/application/query/OrderView.java`
- `order/application/query/OrderViewStore.java`
- `order/infrastructure/persistence/InMemoryOrderRepository.java`
- `order/infrastructure/projection/OrderProjectionHandler.java`
- `order/infrastructure/projection/InMemoryOrderViewStore.java`
- `order/infrastructure/listener/OrderNotificationHandler.java`
- `order/interfaces/rest/OrderController.java`
- `order/interfaces/rest/GlobalExceptionHandler.java` — 更新 DomainException import
- `order/interfaces/rest/dto/EventResponse.java`
- `order/interfaces/rest/dto/PlaceOrderRequest.java`

- [ ] **Step 6: 创建本地 InventoryReservationFailedEvent**

在 order-service 内定义消费的事件 record：

```java
// demo-order-service/src/main/java/com/example/demo/order/domain/event/InventoryReservationFailedEvent.java
package com.example.demo.order.domain.event;

import com.example.demo.eventdriven.domain.DomainEvent;
import java.time.Instant;

public record InventoryReservationFailedEvent(
        String eventId,
        String orderId,
        String productId,
        int requiredQuantity,
        int availableQuantity,
        String reason,
        Instant occurredOn
) implements DomainEvent {

    @Override
    public String aggregateId() {
        return orderId;
    }

    @Override
    public String eventType() {
        return "InventoryReservationFailedEvent";
    }
}
```

- [ ] **Step 7: 迁移 InventoryFailureCompensationHandler**

更新 import 指向 order-service 本地的 `InventoryReservationFailedEvent`。

- [ ] **Step 8: 创建 DomainEventStore + DomainEventAuditListener**

从 demo-core 复制 `shared/infrastructure/event/DomainEventStore.java` 和 `DomainEventAuditListener.java` 到 `demo-order-service/src/main/java/com/example/demo/infrastructure/event/`。

- [ ] **Step 9: 创建简化版 DiagnosticsController**

移除 `InventoryService` 和 `LoyaltyPointsService` 依赖，只保留 order-service 本地诊断：

```java
// demo-order-service/src/main/java/com/example/demo/admin/DiagnosticsController.java
package com.example.demo.admin;

import com.example.demo.order.infrastructure.projection.OrderProjectionHandler;
import com.example.demo.order.interfaces.rest.dto.EventResponse;
import com.example.demo.infrastructure.event.DomainEventStore;
import com.example.demo.eventdriven.idempotency.ProcessedEventRegistry;
import com.example.demo.eventdriven.outbox.OutboxEvent;
import com.example.demo.eventdriven.outbox.OutboxRelay;
import com.example.demo.eventdriven.outbox.OutboxStore;
// ... imports ...

@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticsController {

    private final DomainEventStore eventStore;
    private final OutboxStore outboxStore;
    private final OutboxRelay outboxRelay;
    private final ProcessedEventRegistry processedEventRegistry;
    private final OrderProjectionHandler projectionHandler;

    public DiagnosticsController(DomainEventStore eventStore,
                                 OutboxStore outboxStore,
                                 OutboxRelay outboxRelay,
                                 ProcessedEventRegistry processedEventRegistry,
                                 OrderProjectionHandler projectionHandler) {
        this.eventStore = eventStore;
        this.outboxStore = outboxStore;
        this.outboxRelay = outboxRelay;
        this.processedEventRegistry = processedEventRegistry;
        this.projectionHandler = projectionHandler;
    }

    @GetMapping("/events")
    public List<EventResponse> events() {
        return eventStore.allEvents().stream().map(EventResponse::from).toList();
    }

    @GetMapping("/outbox")
    public List<OutboxView> outbox() {
        return outboxStore.findAll().stream().map(OutboxView::from).toList();
    }

    @PostMapping("/outbox/flush")
    public Map<String, Object> flushOutbox() {
        int delivered = outboxRelay.flush();
        return Map.of("delivered", delivered, "pending", outboxStore.pendingCount());
    }

    @PostMapping("/projections/rebuild")
    public Map<String, Object> rebuildProjection() {
        int replayed = projectionHandler.rebuild(eventStore.allEvents());
        return Map.of("replayedEvents", replayed);
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("eventCount", eventStore.size());
        stats.put("outboxTotal", outboxStore.size());
        stats.put("outboxPending", outboxStore.pendingCount());
        stats.put("processedEvents", processedEventRegistry.size());
        return stats;
    }

    public record OutboxView(String id, String eventId, String eventType, String aggregateId,
                             String status, String payload, java.time.Instant occurredOn) {
        static OutboxView from(OutboxEvent event) {
            return new OutboxView(event.id(), event.eventId(), event.eventType(),
                    event.aggregateId(), event.getStatus(), event.payload(), event.occurredOn());
        }
    }
}
```

- [ ] **Step 10: 创建 OrderServiceEventConsumer**

```java
// demo-order-service/src/main/java/com/example/demo/infrastructure/rabbitmq/OrderServiceEventConsumer.java
package com.example.demo.infrastructure.rabbitmq;

import com.example.demo.eventdriven.domain.DomainEvent;
import com.example.demo.eventdriven.rabbitmq.EventConsumer;
import com.example.demo.order.domain.event.InventoryReservationFailedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
public class OrderServiceEventConsumer implements EventConsumer {

    @Override
    public DomainEvent deserialize(String eventId, String eventType,
                                   String aggregateId, JsonNode payload) {
        return switch (eventType) {
            case "InventoryReservationFailedEvent" ->
                deserializeInventoryReservationFailedEvent(eventId, aggregateId, payload);
            default -> null;  // order-service 只消费库存失败事件
        };
    }

    private InventoryReservationFailedEvent deserializeInventoryReservationFailedEvent(
            String eventId, String aggregateId, JsonNode payload) {
        return new InventoryReservationFailedEvent(
                eventId,
                payload.get("orderId").asText(),
                payload.get("productId").asText(),
                payload.get("requiredQuantity").asInt(),
                payload.get("availableQuantity").asInt(),
                payload.get("reason").asText(),
                Instant.parse(payload.get("occurredOn").asText())
        );
    }
}
```

- [ ] **Step 11: 迁移 WebConfig**

复制 `WebConfig.java` 到 `demo-order-service/src/main/java/com/example/demo/infrastructure/web/WebConfig.java`，更新 CORS 允许 9001 端口。

- [ ] **Step 12: 复制测试页面**

复制 `demo-core/src/main/resources/static/index.html` 到 `demo-order-service/src/main/resources/static/index.html`，更新 API 地址为 `http://localhost:9001`。

- [ ] **Step 13: 编译验证**

Run: `./mvnw compile -pl demo-order-service -am`
Expected: BUILD SUCCESS

- [ ] **Step 14: Commit**

```bash
git add demo-order-service/
git commit -m "feat: create demo-order-service with full order context"
```

---

### Task 3: 创建 demo-inventory-service

**Files:**
- Create: `demo-inventory-service/pom.xml`
- Create: `demo-inventory-service/src/main/java/com/example/demo/InventoryServiceApplication.java`
- Create: `demo-inventory-service/src/main/resources/application.yaml`
- Create: ~8 Java files

**Interfaces:**
- Consumes: `event-driven-spring-boot-starter`
- Produces: 独立可运行的库存服务，端口 9002

- [ ] **Step 1: 创建 pom.xml**

与 order-service 结构相同，`artifactId` 为 `demo-inventory-service`，`name` 为 `demo-inventory-service`。

- [ ] **Step 2: 创建 Application 类 + application.yaml**

```java
// demo-inventory-service/src/main/java/com/example/demo/InventoryServiceApplication.java
package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class InventoryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
```

```yaml
# demo-inventory-service/src/main/resources/application.yaml
spring:
  application:
    name: demo-inventory-service
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest

server:
  port: 9002

eventdriven:
  rabbitmq:
    queue: ${spring.application.name}.queue

logging:
  level:
    root: INFO
    com.example.demo: INFO
  pattern:
    console: "%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n"
```

- [ ] **Step 3: 迁移 InventoryService**

从 demo-core 复制 `inventory/InventoryService.java`，无 import 变化。

- [ ] **Step 4: 创建本地事件 record 类**

inventory-service 需要定义自己版本的 OrderCreatedEvent、OrderCancelledEvent 和 OrderItem：

```java
// demo-inventory-service/src/main/java/com/example/demo/inventory/domain/event/OrderItem.java
package com.example.demo.inventory.domain.event;

public record OrderItem(String productId, int quantity) {
}
```

```java
// demo-inventory-service/src/main/java/com/example/demo/inventory/domain/event/OrderCreatedEvent.java
package com.example.demo.inventory.domain.event;

import com.example.demo.eventdriven.domain.DomainEvent;
import java.time.Instant;
import java.util.List;

public record OrderCreatedEvent(
        String eventId,
        String orderId,
        String customerId,
        List<OrderItem> items,
        Instant occurredOn
) implements DomainEvent {

    public OrderCreatedEvent {
        items = items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public String aggregateId() {
        return orderId;
    }

    @Override
    public String eventType() {
        return "OrderCreatedEvent";
    }
}
```

```java
// demo-inventory-service/src/main/java/com/example/demo/inventory/domain/event/OrderCancelledEvent.java
package com.example.demo.inventory.domain.event;

import com.example.demo.eventdriven.domain.DomainEvent;
import java.time.Instant;
import java.util.List;

public record OrderCancelledEvent(
        String eventId,
        String orderId,
        String reason,
        List<OrderItem> items,
        Instant occurredOn
) implements DomainEvent {

    public OrderCancelledEvent {
        items = items == null ? List.of() : List.copyOf(items);
    }

    @Override
    public String aggregateId() {
        return orderId;
    }

    @Override
    public String eventType() {
        return "OrderCancelledEvent";
    }
}
```

保留原有的 `InventoryReservationFailedEvent`（发布用）。

- [ ] **Step 5: 迁移 InventoryEventListener**

更新 import 指向本地定义的 `OrderCreatedEvent`、`OrderCancelledEvent`、`OrderItem`。

- [ ] **Step 6: 创建 InventoryServiceEventConsumer**

```java
// demo-inventory-service/src/main/java/com/example/demo/infrastructure/rabbitmq/InventoryServiceEventConsumer.java
package com.example.demo.infrastructure.rabbitmq;

import com.example.demo.eventdriven.domain.DomainEvent;
import com.example.demo.eventdriven.rabbitmq.EventConsumer;
import com.example.demo.inventory.domain.event.OrderCreatedEvent;
import com.example.demo.inventory.domain.event.OrderCancelledEvent;
import com.example.demo.inventory.domain.event.OrderItem;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class InventoryServiceEventConsumer implements EventConsumer {

    @Override
    public DomainEvent deserialize(String eventId, String eventType,
                                   String aggregateId, JsonNode payload) {
        return switch (eventType) {
            case "OrderCreatedEvent" -> deserializeOrderCreatedEvent(eventId, aggregateId, payload);
            case "OrderCancelledEvent" -> deserializeOrderCancelledEvent(eventId, aggregateId, payload);
            default -> null;
        };
    }

    private OrderCreatedEvent deserializeOrderCreatedEvent(String eventId, String aggregateId, JsonNode payload) {
        return new OrderCreatedEvent(
                eventId,
                aggregateId,
                payload.get("customerId").asText(),
                deserializeOrderItems(payload.get("items")),
                Instant.parse(payload.get("occurredOn").asText())
        );
    }

    private OrderCancelledEvent deserializeOrderCancelledEvent(String eventId, String aggregateId, JsonNode payload) {
        return new OrderCancelledEvent(
                eventId,
                aggregateId,
                payload.get("reason").asText(),
                deserializeOrderItems(payload.get("items")),
                Instant.parse(payload.get("occurredOn").asText())
        );
    }

    private List<OrderItem> deserializeOrderItems(JsonNode itemsNode) {
        List<OrderItem> items = new ArrayList<>();
        if (itemsNode != null && itemsNode.isArray()) {
            for (JsonNode itemNode : itemsNode) {
                items.add(new OrderItem(
                        itemNode.get("productId").asText(),
                        itemNode.get("quantity").asInt()
                ));
            }
        }
        return items;
    }
}
```

- [ ] **Step 7: 创建可选的 InventoryController**

```java
// demo-inventory-service/src/main/java/com/example/demo/inventory/interfaces/InventoryController.java
package com.example.demo.inventory.interfaces;

import com.example.demo.inventory.InventoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/stock")
    public Map<String, Integer> stock() {
        return inventoryService.stockSnapshot();
    }

    @GetMapping("/reserved")
    public Map<String, Integer> reserved() {
        return inventoryService.reservedSnapshot();
    }
}
```

- [ ] **Step 8: 编译验证**

Run: `./mvnw compile -pl demo-inventory-service -am`
Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add demo-inventory-service/
git commit -m "feat: create demo-inventory-service with stock reservation"
```

---

### Task 4: 创建 demo-loyalty-service

**Files:**
- Create: `demo-loyalty-service/pom.xml`
- Create: `demo-loyalty-service/src/main/java/com/example/demo/LoyaltyServiceApplication.java`
- Create: `demo-loyalty-service/src/main/resources/application.yaml`
- Create: ~5 Java files

**Interfaces:**
- Consumes: `event-driven-spring-boot-starter`
- Produces: 独立可运行的积分服务，端口 9003

- [ ] **Step 1: 创建 pom.xml + Application 类 + application.yaml**

与 inventory-service 结构相同，端口 9003，`spring.application.name: demo-loyalty-service`。

- [ ] **Step 2: 迁移 LoyaltyPointsService**

从 demo-core 复制，无 import 变化。

- [ ] **Step 3: 创建本地事件 record 类**

```java
// demo-loyalty-service/src/main/java/com/example/demo/loyalty/domain/event/Money.java
package com.example.demo.loyalty.domain.event;

import java.math.BigDecimal;

public record Money(BigDecimal amount, String currency) {
}
```

```java
// demo-loyalty-service/src/main/java/com/example/demo/loyalty/domain/event/OrderPaidEvent.java
package com.example.demo.loyalty.domain.event;

import com.example.demo.eventdriven.domain.DomainEvent;
import java.time.Instant;

public record OrderPaidEvent(
        String eventId,
        String orderId,
        String customerId,
        Money paidAmount,
        String paymentId,
        Instant occurredOn
) implements DomainEvent {

    @Override
    public String aggregateId() {
        return orderId;
    }

    @Override
    public String eventType() {
        return "OrderPaidEvent";
    }
}
```

- [ ] **Step 4: 迁移 LoyaltyEventListener**

更新 import 指向本地定义的 `OrderPaidEvent` 和 `Money`。

- [ ] **Step 5: 创建 LoyaltyServiceEventConsumer**

```java
// demo-loyalty-service/src/main/java/com/example/demo/infrastructure/rabbitmq/LoyaltyServiceEventConsumer.java
package com.example.demo.infrastructure.rabbitmq;

import com.example.demo.eventdriven.domain.DomainEvent;
import com.example.demo.eventdriven.rabbitmq.EventConsumer;
import com.example.demo.loyalty.domain.event.Money;
import com.example.demo.loyalty.domain.event.OrderPaidEvent;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Instant;

@Component
public class LoyaltyServiceEventConsumer implements EventConsumer {

    @Override
    public DomainEvent deserialize(String eventId, String eventType,
                                   String aggregateId, JsonNode payload) {
        if (!"OrderPaidEvent".equals(eventType)) {
            return null;
        }
        return new OrderPaidEvent(
                eventId,
                aggregateId,
                payload.get("customerId").asText(),
                deserializeMoney(payload.get("paidAmount")),
                payload.get("paymentId").asText(),
                Instant.parse(payload.get("occurredOn").asText())
        );
    }

    private Money deserializeMoney(JsonNode moneyNode) {
        return new Money(
                new BigDecimal(moneyNode.get("amount").asDouble()),
                moneyNode.get("currency").asText()
        );
    }
}
```

- [ ] **Step 6: 编译验证**

Run: `./mvnw compile -pl demo-loyalty-service -am`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add demo-loyalty-service/
git commit -m "feat: create demo-loyalty-service with points earning"
```

---

### Task 5: 更新 parent pom + 删除 demo-core

**Files:**
- Modify: `pom.xml`
- Delete: `demo-core/` (整个目录)

- [ ] **Step 1: 更新 parent pom.xml**

```xml
<modules>
    <module>event-driven-spring-boot-starter</module>
    <module>demo-order-service</module>
    <module>demo-inventory-service</module>
    <module>demo-loyalty-service</module>
    <module>demo-rabbitmq</module>
    <module>demo-rocketmq</module>
</modules>
```

- [ ] **Step 2: 删除 demo-core 目录**

```bash
rm -rf demo-core/
```

- [ ] **Step 3: 全量编译验证**

Run: `./mvnw clean compile`
Expected: BUILD SUCCESS（所有模块）

- [ ] **Step 4: 运行 starter 测试**

Run: `./mvnw test -pl event-driven-spring-boot-starter`
Expected: 16 tests pass

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: replace demo-core with 3 microservices, update parent pom"
```

---

### Task 6: 集成验证

- [ ] **Step 1: 启动 RabbitMQ**

```bash
docker compose up -d rabbitmq
```

- [ ] **Step 2: 依次启动三个服务**

分别在 3 个终端启动：
```bash
./mvnw spring-boot:run -pl demo-order-service
./mvnw spring-boot:run -pl demo-inventory-service
./mvnw spring-boot:run -pl demo-loyalty-service
```

- [ ] **Step 3: 测试下单流程**

```bash
# 下单
curl -X POST http://localhost:9001/api/orders \
  -H "Content-Type: application/json" \
  -d '{"customerId":"C-001","items":[{"productId":"P-1001","productName":"键盘","unitPrice":399.00,"quantity":2}]}'

# 查看库存（确认已预占）
curl http://localhost:9002/api/inventory/stock

# 支付
curl -X POST http://localhost:9001/api/orders/{orderId}/pay \
  -H "Content-Type: application/json" \
  -d '{"paymentId":"PAY-001"}'
```

- [ ] **Step 4: 验证事件流转**

检查三个服务的日志，确认：
1. order-service 发布 OrderCreatedEvent → inventory-service 消费并预占库存
2. order-service 发布 OrderPaidEvent → loyalty-service 消费并累计积分
3. 所有事件通过 RabbitMQ exchange 正确路由到各自队列

- [ ] **Step 5: 最终 Commit**

```bash
git add -A
git commit -m "docs: update test page for microservice architecture"
```
