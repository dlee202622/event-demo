# DDD 事件驱动 Demo（订单上下文）

一个**可以直接跑起来**的 Spring Boot 3 分层演示工程，用「下单 → 确认 → 支付 / 取消」这条主线，
把 DDD 战术设计 + 领域事件驱动 + CQRS + Outbox 可靠投递串成一条完整链路。

> 技术栈：Java 21 / Spring Boot 4.1（Web MVC）/ 无外部中间件依赖，启动即用。
> 所有存储都是内存实现，替换成 MySQL、Kafka、RocketMQ 时**领域层与应用层代码不需要改动**。

---

## 1. 快速开始

```bash
# 启动（默认 8080）
./mvnw spring-boot:run

# 跑测试（覆盖聚合不变量、事件驱动下游、幂等、读模型重建）
./mvnw test
```

一次完整业务流程：

```bash
# 1) 下单：总价 399 × 2 = 798，触发 OrderCreatedEvent
curl -s -X POST http://localhost:8080/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"C-1001","items":[{"productId":"P-1001","productName":"机械键盘","unitPrice":399.00,"quantity":2}]}'

# 2) 确认订单：触发 OrderConfirmedEvent
curl -s -X POST http://localhost:8080/api/orders/{orderId}/confirm

# 3) 支付：触发 OrderPaidEvent → 发放 79 积分
curl -s -X POST http://localhost:8080/api/orders/{orderId}/pay \
  -H 'Content-Type: application/json' -d '{"paymentId":"PAY-20261026-001"}'

# 4) 查读模型（CQRS 查询侧）
curl -s http://localhost:8080/api/orders/{orderId}

# 5) 查事件流（审计 / 排障 / 事件溯源）
curl -s http://localhost:8080/api/orders/{orderId}/events

# 6) 观察全局：事件、发件箱、库存、积分
curl -s http://localhost:8080/api/diagnostics/stats
curl -s -X POST http://localhost:8080/api/diagnostics/outbox/flush   # 手动触发投递
curl -s -X POST http://localhost:8080/api/diagnostics/projections/rebuild  # 读模型全量重建
```

---

## 2. 分层结构（依赖方向：外层 → 内层，内层不感知外层）

```
src/main/java/com/example/demo
├── DemoApplication.java                 启动类（@EnableScheduling 驱动 Outbox 中继）
│
├── shared/                              ── 共享内核 ──
│   ├── domain/
│   │   ├── DomainEvent.java             领域事件契约（eventId / aggregateId / occurredOn）
│   │   ├── AggregateRoot.java           聚合根基类：登记 + 拉取事件
│   │   ├── DomainEventPublisher.java    事件发布端口（依赖倒置）
│   │   └── DomainException.java         领域异常（业务规则冲突）
│   └── infrastructure/
│       ├── event/SpringDomainEventPublisher.java   端口实现：写入事件存储 + Spring 事件多播
│       ├── event/DomainEventStore.java             事件存储（审计 / 回放）
│       ├── outbox/{OutboxEvent,OutboxStore,OutboxRelay,DomainEventOutboxHandler}  可靠投递
│       └── idempotency/ProcessedEventRegistry.java 消费端幂等登记表
│
├── order/                               ── 订单限界上下文 ──
│   ├── domain/                          核心：零框架依赖
│   │   ├── model/{Order,OrderId,OrderItem,Money,OrderStatus}
│   │   ├── event/{OrderCreated,OrderConfirmed,OrderPaid,OrderCancelled}Event
│   │   └── repository/{OrderRepository,OrderNotFoundException}
│   ├── application/                     应用层：只编排，不含业务规则
│   │   ├── OrderApplicationService.java
│   │   ├── command/PlaceOrderCommand.java
│   │   └── query/{OrderView,OrderViewStore,OrderQueryService}
│   ├── infrastructure/                  基础设施：端口实现
│   │   ├── persistence/InMemoryOrderRepository.java
│   │   ├── projection/{InMemoryOrderViewStore,OrderProjectionHandler}.java
│   │   └── listener/OrderNotificationHandler.java
│   └── interfaces/rest/                接口层：HTTP 防腐
│       ├── OrderController.java
│       ├── GlobalExceptionHandler.java
│       └── dto/{PlaceOrderRequest,EventResponse}.java
│
├── inventory/                           ── 库存上下文（订阅 OrderCreated / OrderCancelled）──
│   ├── InventoryService.java
│   └── event/InventoryEventListener.java
│
├── loyalty/                             ── 积分上下文（订阅 OrderPaid，带幂等）──
│   ├── LoyaltyPointsService.java
│   └── event/LoyaltyEventListener.java
│
└── admin/DiagnosticsController.java     诊断接口（把链路中间态暴露出来）
```

---

## 3. 事件流全景

```
                 HTTP
                  │  POST /api/orders
                  ▼
        ┌──────────────────────┐
        │  OrderController     │  接口层：HTTP → Command
        └──────────┬───────────┘
                   ▼
        ┌──────────────────────┐
        │ OrderApplicationSvc  │  ① 创建聚合 ② save ③ 发布事件
        └──────────┬───────────┘
                   ▼
        ┌──────────────────────┐
        │ Order（聚合根）       │  业务校验 + 状态流转 + registerEvent
        └──────────┬───────────┘
                   │  pullDomainEvents()
                   ▼
        ┌──────────────────────┐
        │ DomainEventPublisher │  ① 写 EventStore ② 交给 Spring 多播
        └──────────┬───────────┘
                   │  OrderCreatedEvent
      ┌────────────┼───────────────┬──────────────────┬─────────────────┐
      ▼            ▼               ▼                  ▼                 ▼
  库存上下文    读模型投影      通知/短信          Outbox 发件箱      事件存储
  预占库存      upsert View     日志              待投递 MQ          审计/回放
                                                                        │
                                                        OutboxRelay 定时投递
                                                                        ▼
                                                              Kafka / RocketMQ
                                                              topic=order-events
                                                              key=orderId（保序）
```

支付时 `OrderPaidEvent` 再驱动：读模型状态 → PAID、积分 +79、支付回执通知。

---

## 4. 关键设计点（面试/评审常问）

| 关注点 | 本项目的做法 | 生产建议 |
| --- | --- | --- |
| 事件何时发布 | 聚合内登记、**保存聚合之后**统一发布 | 与业务数据同事务写 Outbox，再由中继投递 |
| 领域层是否依赖框架 | 否。`Order` 只依赖 `AggregateRoot`/`DomainException` | 保持一致，领域层可单测、可迁移 |
| 事件与业务数据的原子性 | `OutboxStore` 模拟 outbox 表 + `OutboxRelay` 轮询投递 | outbox 表 + `SKIP LOCKED` 抢占 / CDC（Debezium） |
| 重复投递 | `ProcessedEventRegistry` 按 eventId 去重 | Redis SETNX + TTL，或 `unique(event_id, group)` |
| 事件顺序 | 内存顺序 + 投递 key=aggregateId | Kafka 分区 key = aggregateId，同聚合严格有序 |
| 读写分离 | `OrderView` 读模型由投影维护，查询不碰聚合 | 读模型落 ES / 只读库，支持独立扩容 |
| 读模型重建 | `POST /api/diagnostics/projections/rebuild` 按事件流重放 | 版本化投影 + 全量重放 + 影子表切换 |
| 跨上下文耦合 | 只共享事件契约，不共享聚合/仓储 | 事件做版本兼容（只增字段不删不改语义） |
| 失败补偿 | 库存不足仅告警（demo 简化） | 发布 `StockReservationFailedEvent` 驱动订单取消（Saga） |

---

## 5. 值得注意的几处代码

- **`Order` 是唯一能改状态的地方**：`place/confirm/pay/cancel` 每个方法都是「校验 → 改状态 → 登记事件」三段式，
  外部拿不到 setter，所以不变量不可能被绕过。
- **`OrderApplicationService` 只有 4 个用例、每个 5 行**：所有业务规则在聚合里，
  用例层加日志/权限/事务都很薄。新增「退款」用例只需加聚合方法 + 用例方法。
- **`OrderCancelledEvent` 携带 items 快照**：库存上下文释放占用时无需回调订单服务，
  避免「伪装成事件的同步调用」。
- **`LoyaltyEventListener` 先做幂等再发积分**：演示 at-least-once 语义下的必备动作。
- **`OrderProjectionHandler.rebuild()`**：清空读模型 + 重放事件流即可恢复，
  这正是事件驱动相对传统 CRUD 的最大红利之一。

---

## 6. 接真实中间件的最小改动

1. `pom.xml` 加 `spring-kafka`（或 `rocketmq-spring-boot-starter`）。
2. `OutboxRelay.flush()` 里把 `log.info` 换成 `kafkaTemplate.send(topic, aggregateId, payload)`，
   成功回调中 `event.markDelivered()`，失败重试 + 死信表。
3. 下游上下文把 `@EventListener` 换成 `@KafkaListener(topics = "order-events")`，
   消费逻辑（幂等 + 业务处理）完全复用。
4. 需要跨实例事务一致性时，把内存 Store 换成 MySQL/JPA，`@Transactional` 包住
   「业务写入 + outbox 写入」，投递交给中继即可。

`application.yaml` 中已留好 Kafka 生产者配置示例（注释状态）。
