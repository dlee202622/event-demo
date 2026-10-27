# Event-Driven Spring Boot Starter

基于 DDD 和事件驱动架构的 Spring Boot Starter，集成 RabbitMQ 实现异步事件处理和 Outbox Pattern。

## 特性

- ✅ **Outbox Pattern**：保证业务数据落库和事件投递的最终一致性
- ✅ **幂等性保证**：避免事件重复处理
- ✅ **自动配置**：开箱即用，最小化配置
- ✅ **可扩展**：支持自定义事件反序列化逻辑
- ✅ **死信队列**：自动处理失败消息

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>event-driven-spring-boot-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

### 2. 配置 application.yaml

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest

eventdriven:
  enabled: true
  outbox:
    relay-enabled: true
    relay-interval-ms: 3000
  rabbitmq:
    exchange: event-driven.exchange
    queue: event-driven.queue
```

### 3. 定义领域事件

```java
public record OrderCreatedEvent(
    String eventId,
    String orderId,
    String customerId,
    Money totalAmount,
    List<OrderItem> items,
    Instant occurredOn
) implements DomainEvent {
    
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

### 4. 发布事件

```java
@Service
public class OrderApplicationService {
    
    private final DomainEventPublisher eventPublisher;
    
    public OrderId placeOrder(PlaceOrderCommand command) {
        Order order = Order.place(orderRepository.nextId(), command.customerId(), items);
        orderRepository.save(order);
        
        // 发布领域事件
        eventPublisher.publish(order.pullDomainEvents());
        
        return order.id();
    }
}
```

### 5. 监听事件

```java
@Component
public class InventoryEventListener {
    
    @EventListener
    public void on(OrderCreatedEvent event) {
        // 处理订单创建事件（如预占库存）
        inventoryService.reserve(event.items());
    }
}
```

### 6. 自定义事件反序列化（可选）

如果需要从 RabbitMQ 消费事件，需要实现 `EventConsumer` 接口：

```java
@Component
public class MyEventConsumer implements EventConsumer {
    
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
        // 反序列化逻辑
        return new OrderCreatedEvent(...);
    }
}
```

## 架构流程

```
┌─────────────┐
│  业务操作    │
│ (创建订单)   │
└──────┬──────┘
       │
       ▼
┌─────────────────┐
│ DomainEventPublisher │
│  (发布领域事件)      │
└──────┬──────────┘
       │
       ├──────────────────┐
       │                  │
       ▼                  ▼
┌─────────────┐    ┌──────────────┐
│ 进程内监听器 │    │ OutboxHandler │
│ (同步处理)   │    │ (写入发件箱)  │
└─────────────┘    └──────┬───────┘
                          │
                          ▼
                   ┌─────────────┐
                   │ OutboxRelay │
                   │ (定时投递)   │
                   └──────┬──────┘
                          │
                          ▼
                   ┌─────────────┐
                   │  RabbitMQ   │
                   └──────┬──────┘
                          │
                          ▼
                   ┌─────────────┐
                   │ EventConsumer│
                   │ (消费事件)   │
                   └──────┬──────┘
                          │
                          ▼
                   ┌─────────────┐
                   │ 重新发布为   │
                   │ Spring Event │
                   └─────────────┘
```

## 配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `eventdriven.enabled` | `true` | 是否启用事件驱动 |
| `eventdriven.outbox.relay-enabled` | `true` | 是否启用 Outbox Relay |
| `eventdriven.outbox.relay-interval-ms` | `3000` | Relay 轮询间隔（毫秒） |
| `eventdriven.outbox.relay-initial-delay-ms` | `2000` | Relay 初始延迟（毫秒） |
| `eventdriven.rabbitmq.exchange` | `event-driven.exchange` | RabbitMQ 交换机名称 |
| `eventdriven.rabbitmq.queue` | `event-driven.queue` | RabbitMQ 队列名称 |
| `eventdriven.rabbitmq.routing-key-prefix` | `event` | 路由 key 前缀 |

## 生产环境建议

### 1. 替换 OutboxStore 为数据库实现

```java
@Bean
public OutboxStore outboxStore(JdbcTemplate jdbcTemplate) {
    return new JdbcOutboxStore(jdbcTemplate);
}
```

### 2. 替换 ProcessedEventRegistry 为 Redis 实现

```java
@Bean
public ProcessedEventRegistry processedEventRegistry(StringRedisTemplate redisTemplate) {
    return new RedisProcessedEventRegistry(redisTemplate);
}
```

### 3. 启用消息确认

```yaml
spring:
  rabbitmq:
    listener:
      simple:
        acknowledge-mode: auto
```

## 示例项目

参考 `demo-core` 模块的完整实现。

## License

MIT
