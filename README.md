# DDD 事件驱动 Demo（订单 / 库存 / 积分三服务）

一个**可以直接跑起来**的多服务演示工程，用「下单 → 确认 → 支付 / 取消」这条主线，
把 DDD 战术设计 + 领域事件驱动 + CQRS + Outbox 可靠投递 + RabbitMQ 跨服务通信串成一条完整链路。

> 技术栈：Java 21 / Spring Boot 4.1（Web MVC）/ RabbitMQ（Topic Exchange）
> 服务内存储均为内存实现；`event-driven-spring-boot-starter` 封装了事件发布、Outbox、幂等消费等通用能力。

---

## 1. 模块结构

```
demo
├── event-driven-spring-boot-starter   事件驱动 starter（DomainEvent / Outbox / RabbitMQ / 幂等）
├── demo-order-service                 订单服务（9001，订单上下文 + 诊断接口）
├── demo-inventory-service             库存服务（9002，订阅 OrderCreated / OrderCancelled）
├── demo-loyalty-service               积分服务（9003，订阅 OrderPaid）
├── demo-rabbitmq                      RabbitMQ 基础用法示例
└── demo-rocketmq                      RocketMQ 基础用法示例
```

服务之间只共享**事件契约**，不共享聚合、仓储或代码模块：每个服务各自定义它消费/发布的事件结构。

---

## 2. 快速开始

前置：本地 RabbitMQ（默认 `localhost:5672`，guest/guest）。仓库根目录提供 `docker-compose.yml`。

```bash
# 构建并安装 starter（服务依赖它）
./mvnw clean install -DskipTests

# 分别启动三个服务（三个终端）
./mvnw -pl demo-order-service spring-boot:run
./mvnw -pl demo-inventory-service spring-boot:run
./mvnw -pl demo-loyalty-service spring-boot:run

# 跑全部测试
./mvnw test
```

浏览器打开 <http://localhost:9001/> 有可视化测试页。

一次完整业务流程：

```bash
# 1) 下单：总价 399 × 2 = 798，触发 OrderCreatedEvent
curl -s -X POST http://localhost:9001/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"customerId":"C-1001","items":[{"productId":"P-1001","productName":"机械键盘","unitPrice":399.00,"quantity":2}]}'

# 2) 确认订单：触发 OrderConfirmedEvent
curl -s -X POST http://localhost:9001/api/orders/{orderId}/confirm

# 3) 支付：触发 OrderPaidEvent → 积分服务发放 79 积分
curl -s -X POST http://localhost:9001/api/orders/{orderId}/pay \
  -H 'Content-Type: application/json' -d '{"paymentId":"PAY-20261026-001"}'

# 4) 查读模型（订单服务）
curl -s http://localhost:9001/api/orders/{orderId}

# 5) 查事件流（审计 / 排障 / 事件溯源）
curl -s http://localhost:9001/api/orders/{orderId}/events

# 6) 跨服务观测
curl -s http://localhost:9002/api/inventory/stock       # 库存余量
curl -s http://localhost:9002/api/inventory/reserved    # 预占量
curl -s http://localhost:9003/api/loyalty/points/C-1001 # 客户积分

# 7) 订单服务诊断
curl -s http://localhost:9001/api/diagnostics/stats
curl -s -X POST http://localhost:9001/api/diagnostics/outbox/flush          # 手动触发投递
curl -s -X POST http://localhost:9001/api/diagnostics/projections/rebuild   # 读模型全量重建
```

补偿路径：下单数量超过库存（如 `P-1003` 要 25、可用 20）时，库存服务发布
`InventoryReservationFailedEvent`，订单服务订阅后自动取消订单。

---

## 3. 消息架构

```
                    ┌──────────────────────────────────────────┐
 OrderCreatedEvent  │  Topic Exchange: event-driven.exchange   │
 OrderPaidEvent ───▶│  routing key: event.{eventType}.{aggId}  │
 OrderCancelledEvent│  3 个队列绑定 event.#                     │
                    └───────┬──────────────┬──────────────┬────┘
                            ▼              ▼              ▼
                order-service.queue  inventory-service  loyalty-service
                （订单/补偿事件）      .queue（下单/取消）  .queue（支付）
```

- **一个交换机 + 每服务一个队列**：广播语义，每个服务都能收到全部事件，按自己的契约过滤。
- **Outbox Pattern**：业务事件先写发件箱，`OutboxRelay` 定时投递到 RabbitMQ，保证至少一次送达。
- **消费幂等**：starter 的 `EventMessageListener` 按 `eventId` 去重，处理成功后才登记，失败可重投。
- **顺序性**：routing key 以 `aggregateId` 结尾，同一聚合的事件可顺序消费。

---

## 4. 关键设计点（面试/评审常问）

| 关注点 | 本项目的做法 | 生产建议 |
| --- | --- | --- |
| 事件何时发布 | 聚合内登记、**保存聚合之后**统一发布 | 与业务数据同事务写 Outbox，再由中继投递 |
| 领域层是否依赖框架 | 否。`Order` 只依赖 `AggregateRoot`/`DomainException`（starter 提供） | 保持一致，领域层可单测、可迁移 |
| 事件与业务数据的原子性 | `OutboxStore` 模拟 outbox 表 + `OutboxRelay` 轮询投递 | outbox 表 + `SKIP LOCKED` 抢占 / CDC（Debezium） |
| 重复投递 | starter 统一按 eventId 去重（处理成功后登记） | Redis SETNX + TTL，或 `unique(event_id, group)` |
| 事件顺序 | routing key = `event.{type}.{aggregateId}` | 分区/队列 key = aggregateId，同聚合严格有序 |
| 跨服务耦合 | 各服务自定义事件契约，不共享代码模块 | 事件做版本兼容（只增字段不删不改语义） |
| 失败补偿 | 库存不足发布 `InventoryReservationFailedEvent` → 订单自动取消 | Saga 编排 + 补偿重试 / 死信人工介入 |
| 读写分离 | `OrderView` 读模型由投影维护，查询不碰聚合 | 读模型落 ES / 只读库，支持独立扩容 |
| 读模型重建 | `POST /api/diagnostics/projections/rebuild` 按事件流重放 | 版本化投影 + 全量重放 + 影子表切换 |

---

## 5. 值得注意的几处代码

- **`Order` 是唯一能改状态的地方**：`place/confirm/pay/cancel` 每个方法都是「校验 → 改状态 → 登记事件」三段式，
  外部拿不到 setter，所以不变量不可能被绕过。
- **`OrderApplicationService` 只有 4 个用例、每个 5 行**：所有业务规则在聚合里，
  用例层加日志/权限/事务都很薄。新增「退款」用例只需加聚合方法 + 用例方法。
- **`OrderCancelledEvent` 携带 items 快照**：库存上下文释放占用时无需回调订单服务，
  避免「伪装成事件的同步调用」。
- **`InventoryService.release` 以预占量为上限释放**：预占失败的明细在取消时不会虚增库存。
- **`OrderProjectionHandler.rebuild()`**：清空读模型 + 重放事件流即可恢复，
  这正是事件驱动相对传统 CRUD 的最大红利之一。

---

## 6. 接真实基础设施的最小改动

1. **消息中间件**：已接入 RabbitMQ；换成 Kafka/RocketMQ 只需替换 starter 里的
   `OutboxRelay` 发送实现与 `EventMessageListener` 的监听注解，业务服务代码不变。
2. **持久化**：把内存版 `OutboxStore` / `ProcessedEventRegistry` / 各仓储替换为 MySQL/JPA，
   用 `@Transactional` 包住「业务写入 + outbox 写入」，投递交给中继。
3. **生产加固**：outbox 表加 `SKIP LOCKED` 抢占、消费端失败重试与死信队列、
   幂等登记表加 TTL、监控 outbox 积压与消费延迟。
