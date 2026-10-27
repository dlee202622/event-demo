# 三服务接入 MySQL 持久化设计

## 概述

把 demo-order-service / demo-inventory-service / demo-loyalty-service 的内存存储全部替换为 MySQL，
保留现有端口-适配器边界：领域层、REST 接口、事件契约、RabbitMQ 拓扑均不变。

## 决策记录

| 决策项 | 选择 | 理由 |
|--------|------|------|
| 覆盖范围 | 三个服务全部持久化 | 重启后订单、库存、积分、Outbox、幂等记录都不丢 |
| 数据库划分 | 单 MySQL 实例，`demo_order` / `demo_inventory` / `demo_loyalty` 三库 | 数据库自治，符合微服务边界 |
| 业务持久层 | Spring Data JPA（独立 PO + Assembler，领域类零注解） | 保持领域层无框架依赖 |
| Starter 持久层 | JdbcTemplate | 不强制向使用方引入 JPA，SKIP LOCKED 实现自然 |
| 表结构管理 | Flyway（starter 以纯资源提供 V0） | 版本化、可重复执行、各服务自治 |
| 集成测试 | Testcontainers（`mysql:8.4`） | 真实 MySQL，避免 H2 方言差异 |

## 数据库与表结构

同一 MySQL 实例（`localhost:3306`，root/123456）。服务连接串带 `createDatabaseIfNotExist=true`，
首次启动自动建库；表由 Flyway 创建。

### starter 提供的表（每个服务库各一份）

`db/event-driven/V0__event_driven_infrastructure.sql`：

```sql
CREATE TABLE IF NOT EXISTS outbox_event (
    id           VARCHAR(36)  NOT NULL PRIMARY KEY,
    event_id     VARCHAR(64)  NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    aggregate_id VARCHAR(64)  NOT NULL,
    payload      LONGTEXT     NOT NULL,
    occurred_on  DATETIME(6)  NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    delivered_at DATETIME(6)  NULL,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_outbox_status_occurred (status, occurred_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS processed_event (
    event_id     VARCHAR(64) NOT NULL PRIMARY KEY,
    processed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### demo_order

```sql
CREATE TABLE orders (
    id           VARCHAR(32)   NOT NULL PRIMARY KEY,
    customer_id  VARCHAR(64)   NOT NULL,
    status       VARCHAR(16)   NOT NULL,
    total_amount DECIMAL(12,2) NOT NULL,
    currency     VARCHAR(8)    NOT NULL,
    payment_id   VARCHAR(64)   NULL,
    created_at   DATETIME(6)   NOT NULL,
    updated_at   DATETIME(6)   NOT NULL
);

CREATE TABLE order_items (
    id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id     VARCHAR(32)   NOT NULL,
    line_no      INT           NOT NULL,
    product_id   VARCHAR(64)   NOT NULL,
    product_name VARCHAR(128)  NOT NULL,
    unit_price   DECIMAL(12,2) NOT NULL,
    currency     VARCHAR(8)    NOT NULL,
    quantity     INT           NOT NULL,
    UNIQUE KEY uk_order_items_line (order_id, line_no),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id)
);

CREATE TABLE order_view (
    order_id     VARCHAR(32)   NOT NULL PRIMARY KEY,
    customer_id  VARCHAR(64)   NOT NULL,
    status       VARCHAR(16)   NOT NULL,
    status_desc  VARCHAR(32)   NOT NULL,
    total_amount DECIMAL(12,2) NOT NULL,
    currency     VARCHAR(8)    NOT NULL,
    item_count   INT           NOT NULL,
    created_at   DATETIME(6)   NOT NULL,
    updated_at   DATETIME(6)   NOT NULL
);

CREATE TABLE domain_event (
    seq          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id     VARCHAR(64)   NOT NULL,
    event_type   VARCHAR(64)   NOT NULL,
    aggregate_id VARCHAR(64)   NOT NULL,
    payload      LONGTEXT      NOT NULL,
    occurred_on  DATETIME(6)   NOT NULL,
    KEY idx_domain_event_aggregate (aggregate_id, seq)
);
```

### demo_inventory

```sql
CREATE TABLE inventory_stock (
    product_id VARCHAR(64) NOT NULL PRIMARY KEY,
    available  INT         NOT NULL,
    reserved   INT         NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

INSERT INTO inventory_stock (product_id, available, reserved)
VALUES ('P-1001', 100, 0), ('P-1002', 50, 0), ('P-1003', 20, 0);
```

### demo_loyalty

```sql
CREATE TABLE loyalty_points (
    customer_id VARCHAR(64) NOT NULL PRIMARY KEY,
    points      INT         NOT NULL,
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
```

## 模块改造

### event-driven-spring-boot-starter

- `OutboxStore` 端口新增：
  - `List<OutboxEvent> claimPending(int limit)`：原子认领，`PENDING → SENDING`
  - `void markDelivered(String outboxId)`：`SENDING → SENT`，写 `delivered_at`
  - `void markFailed(String outboxId)`：`SENDING → PENDING`，下轮重试
- `OutboxRelay.flush()` 改为「认领 → 逐条发送 → 标记」；单条失败只回退该条。
- 新增 `JdbcOutboxStore`：认领用 `SELECT ... FOR UPDATE SKIP LOCKED`，在自身事务内完成。
- 新增 `JdbcProcessedEventRegistry`：`INSERT IGNORE` 返回影响行数实现 `markIfAbsent`。
- `EventDrivenProperties` 新增：
  - `eventdriven.persistence=memory|jdbc`（默认 `memory`）
  - `eventdriven.outbox.claim-batch-size`（默认 100）
- 自动配置：`persistence=jdbc` 且存在 `DataSource` 时创建 JDBC 实现；`memory`（默认）保持现有内存实现。
  自动配置排在 `DataSourceAutoConfiguration` 之后。
- 新增资源 `db/event-driven/V0__event_driven_infrastructure.sql`（starter 不依赖 Flyway）。
- pom 新增 optional `spring-boot-starter-jdbc`；测试增加 Testcontainers。

### demo-order-service

- 新增 `JpaOrderRepository`（implements `OrderRepository`）+ `OrderEntity`/`OrderItemEntity`/`OrderAssembler`。
- 新增 `JpaOrderViewStore`（implements `OrderViewStore`）+ `OrderViewEntity`。
- `DomainEventStore` 重写为 JDBC 实现：payload 用 `ObjectMapper` 序列化，
  `DomainEventCodec` 维护 eventType → Class 映射（4 个订单事件 + InventoryReservationFailedEvent）。
- `OrderApplicationService` 四个用例加 `@Transactional`：订单 + 明细、事件存储、Outbox、读模型同事务提交。
- 删除 `InMemoryOrderRepository`、`InMemoryOrderViewStore`。
- 新增 Flyway `V1__order_schema.sql`。
- `application.yaml` 增加 datasource / jpa / flyway 配置，`eventdriven.persistence: jdbc`。
- 集成测试改用 Testcontainers MySQL。

### demo-inventory-service

- 新增端口 `StockRepository` 与领域值 `Stock(productId, available, reserved)`。
- `JpaStockRepository`：`findByIdForUpdate` 使用 `PESSIMISTIC_WRITE` 行锁。
- `InventoryService.reserve/release` 改为通过仓储读写并加 `@Transactional`；
  释放仍以预占量为上限。
- 删除构造器中的内存初始库存；改由 Flyway 种子数据提供。
- 单元测试注入内存假仓储；新增 Testcontainers 持久化测试。

### demo-loyalty-service

- 新增端口 `PointsRepository`。
- `JdbcPointsRepository`：`INSERT ... AS new ON DUPLICATE KEY UPDATE points = loyalty_points.points + new.points`
  原子累加，避免并发丢更新。
- `LoyaltyPointsService` 委托仓储；单元测试注入内存假仓储；新增 Testcontainers 持久化测试。

## 事务与事件流

```
HTTP 请求
  └─ OrderApplicationService (@Transactional)
       ├─ 保存聚合（orders / order_items）
       ├─ publishEvent(OrderCreatedEvent)
       │    ├─ DomainEventAuditListener  → domain_event
       │    ├─ DomainEventOutboxHandler  → outbox_event (PENDING)
       │    └─ OrderProjectionHandler    → order_view
       └─ COMMIT（以上写入原子生效）

OutboxRelay（定时）
  └─ claimPending (FOR UPDATE SKIP LOCKED, PENDING→SENDING)
       ├─ RabbitMQ 发送成功 → markDelivered (SENDING→SENT)
       └─ 失败 → markFailed (SENDING→PENDING)

MQ 消费
  └─ EventMessageListener
       ├─ isProcessed? → 跳过
       ├─ 发布 Spring 事件 → 各服务监听器（库存预占/积分累加/订单补偿）
       └─ 处理成功 → markIfAbsent（processed_event）
```

## 配置

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/demo_order?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&characterEncoding=utf8
    username: root
    password: ${MYSQL_PASSWORD:123456}
  jpa:
    open-in-view: false
    hibernate.ddl-auto: none
    properties.hibernate.jdbc.time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration,classpath:db/event-driven
eventdriven:
  persistence: jdbc
```

库存/积分服务仅替换库名与端口。`ddl-auto: none`，表结构唯一来源是 Flyway。

## 测试

- starter：`JdbcOutboxStoreTest`、`JdbcProcessedEventRegistryTest`（Testcontainers），覆盖认领状态流转、
  失败回退、重复登记。
- 订单：`OrderServiceIntegrationTest`（Testcontainers）验证订单/明细/事件/Outbox/读模型落库，
  以及投影重建。
- 库存：单元测试（内存假仓储）+ `InventoryPersistenceTest` 验证悲观锁下的预占/释放落库。
- 积分：单元测试（内存假仓储）+ `LoyaltyPersistenceTest` 验证累加 upsert。
- 手工验收：正常流、库存不足补偿、取消归还，随后重启三个服务确认数据仍在。
- Testcontainers 通过 `TESTCONTAINERS_RYUK_DISABLED=true` 适配国内网络；本机已有 `mysql:8.4` 镜像。

## 风险与注意事项

- 消费到的跨服务事件会被再次写入本服务 Outbox 并广播一轮（幂等兜底），行为保持不变。
- `Instant` 统一按 UTC 存 `DATETIME(6)`，连接与 Hibernate 时区都设为 UTC。
- Flyway 版本化后不允许修改已执行脚本；演示阶段可直接清库重建。
- Testcontainers 启动比内存测试慢（每个容器约 15–25 秒），仅用于集成测试。
