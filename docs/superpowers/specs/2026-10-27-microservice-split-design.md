# demo-core 微服务拆分设计

## 概述

将 demo-core 单体应用拆分为 3 个独立微服务，通过 RabbitMQ Topic Exchange 进行事件通信。

## 决策记录

| 决策项 | 选择 | 理由 |
|--------|------|------|
| 拆分粒度 | 3 个服务 | 每个限界上下文独立部署 |
| 事件契约 | 各服务独立定义 | 完全解耦，符合微服务原则 |
| 共享代码 | 移入 starter | AggregateRoot/DomainException 等基类由 starter 统一提供 |
| 管理诊断 | order-service 托管 | DiagnosticsController 放在主服务 |
| 端口 | 9001/9002/9003 | 避免冲突 |
| 消息队列 | 一个 Exchange + 三个 Queue | 事件广播到所有相关服务 |

## 模块结构

```
demo/
├── pom.xml (parent)
├── event-driven-spring-boot-starter/   ← 增强：吸收共享基类 + 可配置队列名
├── demo-order-service/                 ← 端口 9001，主服务
├── demo-inventory-service/             ← 端口 9002
└── demo-loyalty-service/               ← 端口 9003
```

删除 `demo-core` 模块。

## 消息架构

```
                         ┌─→ inventory-service.queue ──→ InventoryService
OrderService ─publish─→ Exchange ─┼─→ loyalty-service.queue ────→ LoyaltyService
                         └─→ order-service.queue ──────→ OrderService
```

- Exchange: `event-driven.exchange` (Topic 类型，所有服务共享)
- 每个服务创建以自己名字命名的队列: `${spring.application.name}.queue`
- 各服务通过 `EventConsumer` 接口实现自定义反序列化，只处理自己关心的事件

## Starter 增强

### 移入共享基类

- `AggregateRoot` ← 来自 demo-core/shared/domain
- `DomainException` ← 来自 demo-core/shared/domain

### 队列名可配置

```yaml
eventdriven:
  rabbitmq:
    queue-name: ${spring.application.name}.queue  # 默认用服务名
```

`EventDrivenProperties` 新增 `rabbitmq.queueName` 属性，`EventRabbitMQConfig` 使用该属性创建队列和绑定。

### 自动配置调整

- `EventRabbitMQConfig` 使用 `queueName` 属性创建队列
- routing key 绑定保持 `event.#`（每个服务接收所有事件，由 EventConsumer 自行过滤）

## 各服务职责

### demo-order-service (9001)

**发布事件:**
- `OrderCreatedEvent`
- `OrderConfirmedEvent`
- `OrderPaidEvent`
- `OrderCancelledEvent`

**消费事件:**
- `InventoryReservationFailedEvent` (补偿：取消订单)

**包含:**
- 订单 CRUD (OrderController)
- 订单领域模型 (Order, OrderId, OrderItem, Money, OrderStatus)
- 应用服务 (OrderApplicationService)
- 查询服务 (OrderQueryService, OrderViewStore)
- 读模型投影 (OrderProjectionHandler)
- 补偿处理器 (InventoryFailureCompensationHandler)
- 通知处理器 (OrderNotificationHandler)
- DiagnosticsController (管理诊断)
- DomainEventStore + DomainEventAuditListener (事件审计)
- DemoCoreEventConsumer (RabbitMQ 事件反序列化)

### demo-inventory-service (9002)

**发布事件:**
- `InventoryReservationFailedEvent`

**消费事件:**
- `OrderCreatedEvent` (预占库存)
- `OrderCancelledEvent` (释放库存)

**包含:**
- InventoryService
- InventoryEventListener
- 库存管理 REST 接口 (可选)
- InventoryEventConsumer (RabbitMQ 事件反序列化)

### demo-loyalty-service (9003)

**发布事件:**
- 无

**消费事件:**
- `OrderPaidEvent` (累计积分)

**包含:**
- LoyaltyPointsService
- LoyaltyEventListener
- LoyaltyEventConsumer (RabbitMQ 事件反序列化)

## 事件类独立定义

每个服务各自定义需要消费的事件 record，不共享 contracts 模块。

例如 inventory-service 内定义自己的 `OrderCreatedEvent`：

```java
package com.example.demo.inventory.domain.event;

public record OrderCreatedEvent(
    String eventId,
    String orderId,
    String customerId,
    List<OrderItem> items,
    Instant occurredOn
) implements DomainEvent { ... }
```

只包含该服务需要的字段。

## 配置

三个服务共用同一个 RabbitMQ 实例（已有的 docker-compose）：

```yaml
spring:
  application:
    name: demo-order-service  # / demo-inventory-service / demo-loyalty-service
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest

server:
  port: 9001  # / 9002 / 9003

eventdriven:
  rabbitmq:
    queue-name: ${spring.application.name}.queue
```

## 迁移步骤

1. 增强 starter：移入 AggregateRoot/DomainException，队列名可配置
2. 创建 demo-order-service：从 demo-core 迁移 order 上下文 + admin + shared 审计
3. 创建 demo-inventory-service：从 demo-core 迁移 inventory 上下文
4. 创建 demo-loyalty-service：从 demo-core 迁移 loyalty 上下文
5. 更新 parent pom.xml：替换 demo-core 为三个新模块
6. 删除 demo-core 模块
7. 编译验证 + 集成测试

## DiagnosticsController 处理

拆分后 DiagnosticsController 只保留 order-service 本地的诊断能力：

- 事件审计（DomainEventStore）
- Outbox 状态（OutboxStore/OutboxRelay）
- 幂等注册表（ProcessedEventRegistry）
- 读模型状态（OrderProjectionHandler）

移除对 InventoryService 和 LoyaltyPointsService 的直接引用。各服务独立后，跨服务状态查看通过各自的健康检查端点实现。

## 风险与注意事项

- Order 和 Inventory 之间存在事件级循环依赖（saga 补偿），拆分后通过 RabbitMQ 异步通信，不会有问题
- 每个服务独立启动，需要 RabbitMQ 先启动
- 测试页面需要更新为三个服务的地址
