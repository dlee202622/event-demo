# 三服务 MySQL 持久化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 order / inventory / loyalty 三个服务的全部内存存储替换为 MySQL：业务聚合、读模型、事件存储、Outbox、幂等记录在重启后不丢失。

**Architecture:** 单 MySQL 实例、三个独立数据库（`demo_order` / `demo_inventory` / `demo_loyalty`）。业务侧用 Spring Data JPA（独立 PO + 实体内映射，领域类零注解），starter 的 Outbox / 幂等表用 JdbcTemplate + `SELECT ... FOR UPDATE SKIP LOCKED`。Flyway 管理表结构：starter 以纯资源形式提供 `V0`，各服务提供自己的 `V1`。starter 默认仍为内存实现，服务通过 `eventdriven.persistence=jdbc` 切换。

**Tech Stack:** Java 21 / Spring Boot 4.1.2 / Spring Data JPA / Spring JDBC / Flyway 12.4.0 (`flyway-mysql`) / MySQL 8.4 (connector 9.7.0) / Testcontainers 2.0.5 (`org.testcontainers:testcontainers-mysql`, `org.testcontainers:testcontainers-junit-jupiter`)。

**Spec:** `docs/superpowers/specs/2026-10-27-mysql-persistence-design.md`

## Global Constraints

- Maven 只能用 `./mvnw`（`mvn` 不在 PATH 上）。
- 领域层禁止引入框架注解；JPA 注解只出现在 `infrastructure/persistence/jpa` 下的 PO 上。
- 表结构唯一来源是 Flyway，`spring.jpa.hibernate.ddl-auto` 必须为 `none`。
- `Instant` 统一按 UTC 写入 `DATETIME(6)`；服务连接串带 `connectionTimeZone=UTC`，Hibernate 配置 `hibernate.jdbc.time_zone: UTC`。
- 服务连接串带 `createDatabaseIfNotExist=true`，默认 root/`123456`（可用 `MYSQL_PASSWORD` 覆盖）。
- 单元测试不得依赖 Docker；只有 `*PersistenceTest` / `*IntegrationTest` 使用 Testcontainers，并标注 `@Testcontainers(disabledWithoutDocker = true)`。
- Testcontainers 使用本机已有的 `mysql:8.4` 镜像；Surefire 需注入 `TESTCONTAINERS_RYUK_DISABLED=true`（国内网络）。
- 不改动 RabbitMQ 拓扑、事件契约、REST 路径与端口（9001/9002/9003）。
- 每完成一个任务立即提交；提交信息用 `feat(persistence): ...` / `refactor(persistence): ...`。

---

## Task 0: 提交设计文档与 MySQL 基础设施

**Files:**
- Add: `docs/superpowers/specs/2026-10-27-mysql-persistence-design.md`（已存在）
- Add: `docs/superpowers/plans/2026-10-27-mysql-persistence.md`（本文件）
- Modify: `docker-compose.yml`（已包含 mysql 服务与 `mysql-data` volume）

**Interfaces:**
- Consumes: 无
- Produces: 后续任务依赖的 MySQL 容器（`localhost:3306`，root/`123456`，库 `demo`）

- [ ] **Step 1: 确认 MySQL 容器可启动**

Run:
```bash
export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"
docker compose up -d mysql
docker compose ps mysql
```
Expected: 容器状态 `healthy`，`docker compose exec mysql mysqladmin ping -uroot -p123456` 输出 `mysqld is alive`。

- [ ] **Step 2: 提交文档与 compose**

```bash
git add docker-compose.yml docs/superpowers/specs/2026-10-27-mysql-persistence-design.md docs/superpowers/plans/2026-10-27-mysql-persistence.md
git commit -m "docs(persistence): add MySQL persistence design and plan"
```

---

## Task 1: Starter —— JDBC Outbox + 幂等注册表

**Files:**
- Modify: `pom.xml`（Surefire 注入 `TESTCONTAINERS_RYUK_DISABLED=true`）
- Modify: `event-driven-spring-boot-starter/pom.xml`
- Modify: `event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/outbox/OutboxEvent.java`
- Add: `event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/outbox/OutboxStatus.java`
- Modify: `event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/outbox/OutboxStore.java`
- Modify: `event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/outbox/InMemoryOutboxStore.java`
- Add: `event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/outbox/JdbcOutboxStore.java`
- Add: `event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/idempotency/JdbcProcessedEventRegistry.java`
- Modify: `event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/outbox/OutboxRelay.java`
- Modify: `event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/autoconfigure/EventDrivenProperties.java`
- Modify: `event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/autoconfigure/EventDrivenAutoConfiguration.java`
- Add: `event-driven-spring-boot-starter/src/main/resources/db/event-driven/V0__event_driven_infrastructure.sql`
- Modify: `event-driven-spring-boot-starter/src/test/java/com/example/demo/eventdriven/outbox/InMemoryOutboxStoreTest.java`
- Modify: `event-driven-spring-boot-starter/src/test/java/com/example/demo/eventdriven/autoconfigure/EventDrivenAutoConfigurationTest.java`
- Add: `event-driven-spring-boot-starter/src/test/java/com/example/demo/eventdriven/outbox/JdbcEventDrivenStoreTest.java`

**Interfaces:**
- Consumes: 无
- Produces:
  - `OutboxStore.claimPending(int limit): List<OutboxEvent>`（`PENDING → SENDING`）
  - `OutboxStore.markDelivered(String outboxId)` / `OutboxStore.markFailed(String outboxId)`
  - `OutboxEvent.status(): OutboxStatus`、`OutboxEvent.markDelivered()`、`OutboxEvent.markFailed()`
  - `EventDrivenProperties.getPersistence(): String`（`memory` | `jdbc`）
  - `EventDrivenProperties.Outbox.getClaimBatchSize(): int`（默认 100）
  - `JdbcOutboxStore(JdbcTemplate, DataSource)`、`JdbcProcessedEventRegistry(JdbcTemplate)`

- [ ] **Step 1: 在父 pom 注入 Surefire 环境变量**

`pom.xml` 在 `</modules>` 之后追加：

```xml
    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <configuration>
                        <environmentVariables>
                            <!-- 国内网络下 Ryuk 容器拉取镜像会卡住，集成测试关闭它 -->
                            <TESTCONTAINERS_RYUK_DISABLED>true</TESTCONTAINERS_RYUK_DISABLED>
                        </environmentVariables>
                    </configuration>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
```

- [ ] **Step 2: starter pom 增加 JDBC（optional）与测试依赖**

在 `event-driven-spring-boot-starter/pom.xml` 的 `spring-boot-starter-amqp` 依赖之后追加：

```xml
        <!-- JDBC 持久化（optional：不强制使用方引入 JDBC） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-jdbc</artifactId>
            <optional>true</optional>
        </dependency>
```

在 `</dependencies>` 之前追加测试依赖：

```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-mysql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 3: 新增 `OutboxStatus`**

`event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/outbox/OutboxStatus.java`：

```java
package com.example.demo.eventdriven.outbox;

/**
 * 发件箱事件状态。
 *
 * <p>PENDING → SENDING（认领）→ SENT（投递成功）；失败时 SENDING → PENDING 等待重试。</p>
 */
public enum OutboxStatus {
    PENDING,
    SENDING,
    SENT
}
```

- [ ] **Step 4: 重写 `OutboxEvent`，用状态替代 `AtomicBoolean`**

`event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/outbox/OutboxEvent.java`：

```java
package com.example.demo.eventdriven.outbox;

import java.time.Instant;

/**
 * 发件箱事件记录。
 *
 * <p>用于实现 Outbox Pattern，保证业务数据落库和事件投递的最终一致性。</p>
 */
public class OutboxEvent {

    private final String id;
    private final String eventId;
    private final String eventType;
    private final String aggregateId;
    private final String payload;
    private final Instant occurredOn;
    private OutboxStatus status;

    public OutboxEvent(String id, String eventId, String eventType, String aggregateId,
                       String payload, Instant occurredOn) {
        this(id, eventId, eventType, aggregateId, payload, occurredOn, OutboxStatus.PENDING);
    }

    public OutboxEvent(String id, String eventId, String eventType, String aggregateId,
                       String payload, Instant occurredOn, OutboxStatus status) {
        this.id = id;
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.occurredOn = occurredOn;
        this.status = status;
    }

    public String id() {
        return id;
    }

    public String eventId() {
        return eventId;
    }

    public String eventType() {
        return eventType;
    }

    public String aggregateId() {
        return aggregateId;
    }

    public String payload() {
        return payload;
    }

    public Instant occurredOn() {
        return occurredOn;
    }

    public OutboxStatus status() {
        return status;
    }

    public boolean delivered() {
        return status == OutboxStatus.SENT;
    }

    void markSending() {
        this.status = OutboxStatus.SENDING;
    }

    public void markDelivered() {
        this.status = OutboxStatus.SENT;
    }

    public void markFailed() {
        this.status = OutboxStatus.PENDING;
    }

    public String getStatus() {
        return status.name();
    }
}
```

- [ ] **Step 5: 扩展 `OutboxStore` 端口**

`event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/outbox/OutboxStore.java`：

```java
package com.example.demo.eventdriven.outbox;

import java.util.List;

/**
 * 发件箱存储端口。
 *
 * <p>生产实现应该使用数据库表，与业务数据在同一个本地事务中。</p>
 */
public interface OutboxStore {

    /**
     * 添加事件到发件箱。
     */
    void append(OutboxEvent event);

    /**
     * 原子认领待投递事件：PENDING → SENDING。
     *
     * <p>多实例场景下同一条事件只能被一个实例认领。</p>
     */
    List<OutboxEvent> claimPending(int limit);

    /**
     * 投递成功：SENDING → SENT。
     */
    void markDelivered(String outboxId);

    /**
     * 投递失败：SENDING → PENDING，下一轮重试。
     */
    void markFailed(String outboxId);

    /**
     * 获取所有事件（用于调试/监控）。
     */
    List<OutboxEvent> findAll();

    /**
     * 未投递事件数量。
     */
    int pendingCount();

    /**
     * 总事件数量。
     */
    int size();

    /**
     * 清空所有事件（仅测试用）。
     */
    void clear();
}
```

- [ ] **Step 6: 重写 `InMemoryOutboxStore`**

`event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/outbox/InMemoryOutboxStore.java`：

```java
package com.example.demo.eventdriven.outbox;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版发件箱存储（默认实现）。
 *
 * <p>生产环境应该替换为 {@link JdbcOutboxStore}，使用本地事务保证原子性。</p>
 */
public class InMemoryOutboxStore implements OutboxStore {

    private final Map<String, OutboxEvent> events = new ConcurrentHashMap<>();

    @Override
    public void append(OutboxEvent event) {
        events.put(event.id(), event);
    }

    @Override
    public synchronized List<OutboxEvent> claimPending(int limit) {
        List<OutboxEvent> claimed = events.values().stream()
                .filter(event -> event.status() == OutboxStatus.PENDING)
                .sorted(Comparator.comparing(OutboxEvent::occurredOn)
                        .thenComparing(OutboxEvent::id))
                .limit(limit)
                .toList();
        claimed.forEach(OutboxEvent::markSending);
        return claimed;
    }

    @Override
    public void markDelivered(String outboxId) {
        OutboxEvent event = events.get(outboxId);
        if (event != null) {
            event.markDelivered();
        }
    }

    @Override
    public void markFailed(String outboxId) {
        OutboxEvent event = events.get(outboxId);
        if (event != null) {
            event.markFailed();
        }
    }

    @Override
    public List<OutboxEvent> findAll() {
        return events.values().stream()
                .sorted(Comparator.comparing(OutboxEvent::occurredOn))
                .toList();
    }

    @Override
    public int pendingCount() {
        return (int) events.values().stream()
                .filter(event -> event.status() != OutboxStatus.SENT)
                .count();
    }

    @Override
    public int size() {
        return events.size();
    }

    @Override
    public void clear() {
        events.clear();
    }
}
```

- [ ] **Step 7: 改 `OutboxRelay` 为「认领 → 发送 → 标记」**

`event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/outbox/OutboxRelay.java` 完整替换：

```java
package com.example.demo.eventdriven.outbox;

import com.example.demo.eventdriven.autoconfigure.EventDrivenProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

/**
 * 发件箱中继：定时将待投递事件发送到 RabbitMQ。
 *
 * <p>关键设计点：</p>
 * <ul>
 *   <li>至少一次投递：失败会回退为 PENDING，下一轮重试，消费端必须幂等</li>
 *   <li>顺序性：以 aggregateId 作为 routing key 的一部分</li>
 *   <li>多实例并发：认领交给存储实现（JDBC 版使用 SKIP LOCKED）</li>
 * </ul>
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore outboxStore;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final boolean relayEnabled;
    private final String exchange;
    private final String routingKeyPrefix;
    private final int claimBatchSize;

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
        this.claimBatchSize = properties.getOutbox().getClaimBatchSize();
    }

    @Scheduled(fixedDelayString = "${eventdriven.outbox.relay-interval-ms:3000}",
               initialDelayString = "${eventdriven.outbox.relay-initial-delay-ms:2000}")
    public void relay() {
        if (!relayEnabled) {
            return;
        }
        flush();
    }

    /**
     * 手动触发一轮投递，返回本轮成功发送的条数。
     */
    public synchronized int flush() {
        List<OutboxEvent> batch = outboxStore.claimPending(claimBatchSize);
        if (batch.isEmpty()) {
            return 0;
        }

        int sent = 0;
        for (OutboxEvent event : batch) {
            try {
                String routingKey = buildRoutingKey(event);
                String message = buildMessage(event);

                rabbitTemplate.convertAndSend(exchange, routingKey, message);

                outboxStore.markDelivered(event.id());
                sent++;
                log.info("[Outbox→RabbitMQ] 发送成功 exchange={} routingKey={} eventType={} eventId={}",
                        exchange, routingKey, event.eventType(), event.eventId());

            } catch (Exception e) {
                outboxStore.markFailed(event.id());
                log.warn("[Outbox→RabbitMQ] 发送失败，已回退待重试 eventId={} eventType={} error={}",
                        event.eventId(), event.eventType(), e.getMessage());
            }
        }

        log.info("[Outbox→RabbitMQ] 本轮认领 {} 条、发送成功 {} 条", batch.size(), sent);
        return sent;
    }

    private String buildRoutingKey(OutboxEvent event) {
        return String.format("%s.%s.%s",
                routingKeyPrefix,
                event.eventType(),
                event.aggregateId());
    }

    private String buildMessage(OutboxEvent event) {
        try {
            return String.format(
                    "{\"eventId\":\"%s\",\"eventType\":\"%s\",\"aggregateId\":\"%s\",\"occurredOn\":\"%s\",\"payload\":%s}",
                    event.eventId(),
                    event.eventType(),
                    event.aggregateId(),
                    event.occurredOn(),
                    event.payload()
            );
        } catch (Exception e) {
            log.error("[Outbox] 消息序列化失败 eventId={}", event.eventId(), e);
            throw new RuntimeException("Failed to serialize message", e);
        }
    }
}
```

- [ ] **Step 8: `EventDrivenProperties` 增加 `persistence` 与 `claimBatchSize`**

在 `EventDrivenProperties` 的 `enabled` 字段之后追加：

```java
    /**
     * 持久化方式：memory | jdbc。
     */
    private String persistence = "memory";
```

并追加 getter/setter：

```java
    public String getPersistence() {
        return persistence;
    }

    public void setPersistence(String persistence) {
        this.persistence = persistence;
    }
```

在内部类 `Outbox` 的 `relayInitialDelayMs` 字段之后追加：

```java
        /**
         * 每轮认领的最大事件数。
         */
        private int claimBatchSize = 100;
```

并追加 getter/setter：

```java
        public int getClaimBatchSize() {
            return claimBatchSize;
        }

        public void setClaimBatchSize(int claimBatchSize) {
            this.claimBatchSize = claimBatchSize;
        }
```

- [ ] **Step 9: 新增 `JdbcOutboxStore`**

`event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/outbox/JdbcOutboxStore.java`：

```java
package com.example.demo.eventdriven.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * MySQL 版发件箱存储。
 *
 * <p>append 参与调用方事务（与业务数据同时提交）；认领使用独立事务 +
 * SELECT ... FOR UPDATE SKIP LOCKED，保证多实例下同一条事件只被认领一次。</p>
 */
public class JdbcOutboxStore implements OutboxStore {

    private static final RowMapper<OutboxEvent> ROW_MAPPER = (rs, rowNum) -> new OutboxEvent(
            rs.getString("id"),
            rs.getString("event_id"),
            rs.getString("event_type"),
            rs.getString("aggregate_id"),
            rs.getString("payload"),
            rs.getTimestamp("occurred_on").toInstant(),
            OutboxStatus.valueOf(rs.getString("status"))
    );

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public JdbcOutboxStore(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate = template;
    }

    @Override
    public void append(OutboxEvent event) {
        jdbcTemplate.update(
                "INSERT INTO outbox_event (id, event_id, event_type, aggregate_id, payload, occurred_on, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                event.id(),
                event.eventId(),
                event.eventType(),
                event.aggregateId(),
                event.payload(),
                Timestamp.from(event.occurredOn()),
                OutboxStatus.PENDING.name());
    }

    @Override
    public List<OutboxEvent> claimPending(int limit) {
        return transactionTemplate.execute(status -> {
            List<OutboxEvent> batch = jdbcTemplate.query(
                    "SELECT id, event_id, event_type, aggregate_id, payload, occurred_on, status "
                            + "FROM outbox_event WHERE status = 'PENDING' "
                            + "ORDER BY occurred_on, id LIMIT ? FOR UPDATE SKIP LOCKED",
                    ROW_MAPPER,
                    limit);
            if (batch.isEmpty()) {
                return List.of();
            }
            List<Object[]> claimedIds = batch.stream()
                    .map(event -> new Object[]{event.id()})
                    .toList();
            jdbcTemplate.batchUpdate(
                    "UPDATE outbox_event SET status = 'SENDING' WHERE id = ?", claimedIds);
            batch.forEach(OutboxEvent::markSending);
            return batch;
        });
    }

    @Override
    public void markDelivered(String outboxId) {
        jdbcTemplate.update(
                "UPDATE outbox_event SET status = 'SENT', delivered_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), outboxId);
    }

    @Override
    public void markFailed(String outboxId) {
        jdbcTemplate.update("UPDATE outbox_event SET status = 'PENDING' WHERE id = ?", outboxId);
    }

    @Override
    public List<OutboxEvent> findAll() {
        return jdbcTemplate.query(
                "SELECT id, event_id, event_type, aggregate_id, payload, occurred_on, status "
                        + "FROM outbox_event ORDER BY occurred_on, id",
                ROW_MAPPER);
    }

    @Override
    public int pendingCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE status <> 'SENT'", Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public int size() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event", Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public void clear() {
        jdbcTemplate.update("DELETE FROM outbox_event");
    }
}
```

- [ ] **Step 10: 新增 `JdbcProcessedEventRegistry`**

`event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/idempotency/JdbcProcessedEventRegistry.java`：

```java
package com.example.demo.eventdriven.idempotency;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MySQL 版幂等注册表。
 *
 * <p>markIfAbsent 依赖主键 + INSERT IGNORE：并发消费同一 eventId 时只有一个能拿到 1 行影响数。</p>
 */
public class JdbcProcessedEventRegistry implements ProcessedEventRegistry {

    private final JdbcTemplate jdbcTemplate;

    public JdbcProcessedEventRegistry(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean markIfAbsent(String eventId) {
        return jdbcTemplate.update(
                "INSERT IGNORE INTO processed_event (event_id) VALUES (?)", eventId) > 0;
    }

    @Override
    public boolean isProcessed(String eventId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM processed_event WHERE event_id = ?)",
                Boolean.class, eventId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public int size() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_event", Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public void clear() {
        jdbcTemplate.update("DELETE FROM processed_event");
    }
}
```

- [ ] **Step 11: 自动配置按 `eventdriven.persistence` 选择实现**

`event-driven-spring-boot-starter/src/main/java/com/example/demo/eventdriven/autoconfigure/EventDrivenAutoConfiguration.java` 完整替换：

```java
package com.example.demo.eventdriven.autoconfigure;

import com.example.demo.eventdriven.domain.DomainEventPublisher;
import com.example.demo.eventdriven.idempotency.InMemoryProcessedEventRegistry;
import com.example.demo.eventdriven.idempotency.JdbcProcessedEventRegistry;
import com.example.demo.eventdriven.idempotency.ProcessedEventRegistry;
import com.example.demo.eventdriven.infrastructure.SpringDomainEventPublisher;
import com.example.demo.eventdriven.outbox.*;
import com.example.demo.eventdriven.rabbitmq.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.sql.DataSource;

/**
 * 事件驱动自动配置。
 *
 * <p>Outbox / 幂等存储有两种实现：默认内存版；当 eventdriven.persistence=jdbc
 * 且容器里存在 DataSource/JdbcTemplate 时切换为 MySQL 版。</p>
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration"
})
@EnableScheduling
@EnableConfigurationProperties(EventDrivenProperties.class)
@ConditionalOnProperty(prefix = "eventdriven", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EventDrivenAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper eventDrivenObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // Instant 必须序列化为 ISO-8601 字符串：默认的时间戳格式在 Jackson 2.17+ 会写成科学计数法，
        // 消费端 Instant.parse 无法解析。
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "eventdriven", name = "persistence",
            havingValue = "memory", matchIfMissing = true)
    public OutboxStore inMemoryOutboxStore() {
        return new InMemoryOutboxStore();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "eventdriven", name = "persistence",
            havingValue = "memory", matchIfMissing = true)
    public ProcessedEventRegistry inMemoryProcessedEventRegistry() {
        return new InMemoryProcessedEventRegistry();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(JdbcTemplate.class)
    @ConditionalOnProperty(prefix = "eventdriven", name = "persistence", havingValue = "jdbc")
    static class JdbcPersistenceConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(JdbcTemplate.class)
        public OutboxStore jdbcOutboxStore(JdbcTemplate jdbcTemplate, DataSource dataSource) {
            return new JdbcOutboxStore(jdbcTemplate, dataSource);
        }

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnBean(JdbcTemplate.class)
        public ProcessedEventRegistry jdbcProcessedEventRegistry(JdbcTemplate jdbcTemplate) {
            return new JdbcProcessedEventRegistry(jdbcTemplate);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public DomainEventPublisher domainEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        return new SpringDomainEventPublisher(applicationEventPublisher);
    }

    @Bean
    @ConditionalOnMissingBean
    public DomainEventOutboxHandler domainEventOutboxHandler(OutboxStore outboxStore, ObjectMapper objectMapper) {
        return new DomainEventOutboxHandler(outboxStore, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxRelay outboxRelay(OutboxStore outboxStore,
                                   RabbitTemplate rabbitTemplate,
                                   ObjectMapper objectMapper,
                                   EventDrivenProperties properties) {
        return new OutboxRelay(outboxStore, rabbitTemplate, objectMapper, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventMessageListener eventMessageListener(
            ApplicationEventPublisher applicationEventPublisher,
            ObjectMapper objectMapper,
            ProcessedEventRegistry processedEventRegistry,
            EventConsumer eventConsumer) {
        return new EventMessageListener(
                applicationEventPublisher,
                objectMapper,
                processedEventRegistry,
                eventConsumer
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public EventConsumer eventConsumer() {
        // 默认实现：返回 null，需要用户自定义实现
        return (eventId, eventType, aggregateId, payload) -> null;
    }
}
```

- [ ] **Step 12: 新增 starter 的 Flyway 资源（纯资源，不依赖 Flyway）**

`event-driven-spring-boot-starter/src/main/resources/db/event-driven/V0__event_driven_infrastructure.sql`：

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

- [ ] **Step 13: 更新内存实现测试（`pending()` → `claimPending()`）**

`event-driven-spring-boot-starter/src/test/java/com/example/demo/eventdriven/outbox/InMemoryOutboxStoreTest.java` 完整替换：

```java
package com.example.demo.eventdriven.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryOutboxStoreTest {

    private InMemoryOutboxStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryOutboxStore();
    }

    @Test
    void append_shouldAddEventToStore() {
        store.append(createEvent("event-1", "TestEvent", "agg-1"));

        assertEquals(1, store.size());
        assertEquals(1, store.pendingCount());
    }

    @Test
    void claimPending_shouldReturnOnlyPendingEventsAndMarkThemSending() {
        OutboxEvent event1 = createEvent("event-1", "TestEvent", "agg-1");
        OutboxEvent event2 = createEvent("event-2", "TestEvent", "agg-2");
        store.append(event1);
        store.append(event2);
        event1.markDelivered();

        var claimed = store.claimPending(10);

        assertEquals(1, claimed.size());
        assertEquals("event-2", claimed.get(0).eventId());
        assertEquals(OutboxStatus.SENDING, event2.status());
    }

    @Test
    void claimPending_shouldReturnEventsSortedByOccurredOnAndRespectLimit() {
        Instant now = Instant.now();
        OutboxEvent event1 = new OutboxEvent("1", "event-1", "TestEvent", "agg-1", "{}", now.plusSeconds(2));
        OutboxEvent event2 = new OutboxEvent("2", "event-2", "TestEvent", "agg-2", "{}", now.plusSeconds(1));
        store.append(event1);
        store.append(event2);

        var claimed = store.claimPending(1);

        assertEquals(1, claimed.size());
        assertEquals("event-2", claimed.get(0).eventId());
        assertEquals(OutboxStatus.PENDING, event1.status());
    }

    @Test
    void markDelivered_shouldUpdateStatusById() {
        OutboxEvent event = createEvent("event-1", "TestEvent", "agg-1");
        store.append(event);
        store.claimPending(10);

        store.markDelivered(event.id());

        assertEquals(OutboxStatus.SENT, event.status());
        assertTrue(event.delivered());
        assertEquals(0, store.pendingCount());
    }

    @Test
    void markFailed_shouldReturnEventToPending() {
        OutboxEvent event = createEvent("event-1", "TestEvent", "agg-1");
        store.append(event);
        store.claimPending(10);

        store.markFailed(event.id());

        assertEquals(OutboxStatus.PENDING, event.status());
        assertEquals(1, store.pendingCount());
    }

    @Test
    void clear_shouldRemoveAllEvents() {
        store.append(createEvent("event-1", "TestEvent", "agg-1"));
        store.append(createEvent("event-2", "TestEvent", "agg-2"));

        store.clear();

        assertEquals(0, store.size());
        assertEquals(0, store.pendingCount());
    }

    private OutboxEvent createEvent(String eventId, String eventType, String aggregateId) {
        return new OutboxEvent(eventId, eventId, eventType, aggregateId, "{}", Instant.now());
    }
}
```

- [ ] **Step 14: 更新自动配置测试（补 JDBC 分支）**

`event-driven-spring-boot-starter/src/test/java/com/example/demo/eventdriven/autoconfigure/EventDrivenAutoConfigurationTest.java`：删除内部 `CustomOutboxStore` 的 `pending()` 方法，替换为以下三个方法：

```java
        @Override
        public java.util.List<com.example.demo.eventdriven.outbox.OutboxEvent> claimPending(int limit) {
            return java.util.List.of();
        }

        @Override
        public void markDelivered(String outboxId) {}

        @Override
        public void markFailed(String outboxId) {}
```

并新增测试方法与配置：

```java
    @Test
    void shouldUseJdbcStoresWhenPersistenceIsJdbc() {
        contextRunner
                .withUserConfiguration(RabbitTemplateConfig.class, JdbcConfig.class)
                .withPropertyValues("eventdriven.persistence=jdbc")
                .run(context -> {
                    assertThat(context.getBean(OutboxStore.class))
                            .isInstanceOf(com.example.demo.eventdriven.outbox.JdbcOutboxStore.class);
                    assertThat(context.getBean(ProcessedEventRegistry.class))
                            .isInstanceOf(com.example.demo.eventdriven.idempotency.JdbcProcessedEventRegistry.class);
                });
    }

    @Configuration
    static class JdbcConfig {
        @Bean
        org.springframework.jdbc.core.JdbcTemplate jdbcTemplate() {
            return new org.springframework.jdbc.core.JdbcTemplate();
        }

        @Bean
        javax.sql.DataSource dataSource() {
            return org.mockito.Mockito.mock(javax.sql.DataSource.class);
        }
    }
```

- [ ] **Step 15: 新增 JDBC 存储的 Testcontainers 测试**

`event-driven-spring-boot-starter/src/test/java/com/example/demo/eventdriven/outbox/JdbcEventDrivenStoreTest.java`：

```java
package com.example.demo.eventdriven.outbox;

import com.example.demo.eventdriven.idempotency.JdbcProcessedEventRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class JdbcEventDrivenStoreTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("starter_test")
            .withUsername("test")
            .withPassword("test");

    private static JdbcOutboxStore outboxStore;
    private static JdbcProcessedEventRegistry registry;

    @BeforeAll
    static void setUp() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/event-driven/V0__event_driven_infrastructure.sql"));
        }
        outboxStore = new JdbcOutboxStore(jdbcTemplate, dataSource);
        registry = new JdbcProcessedEventRegistry(jdbcTemplate);
    }

    @BeforeEach
    void clean() {
        outboxStore.clear();
        registry.clear();
    }

    @Test
    @DisplayName("认领：只取 PENDING，状态置为 SENDING，受 limit 限制")
    void claimPending_shouldMoveEventsToSending() {
        outboxStore.append(event("id-1", "event-1", Instant.now().minusSeconds(1)));
        outboxStore.append(event("id-2", "event-2", Instant.now()));

        List<OutboxEvent> claimed = outboxStore.claimPending(1);

        assertEquals(1, claimed.size());
        assertEquals("event-1", claimed.get(0).eventId());
        assertEquals(OutboxStatus.SENDING, claimed.get(0).status());

        // 已被认领的事件不会再次出现在认领结果里
        assertTrue(outboxStore.claimPending(10).stream()
                .noneMatch(event -> event.id().equals("id-1")));
    }

    @Test
    @DisplayName("投递成功：SENDING → SENT，pendingCount 归零")
    void markDelivered_shouldMarkEventSent() {
        outboxStore.append(event("id-1", "event-1", Instant.now()));
        outboxStore.claimPending(10);

        outboxStore.markDelivered("id-1");

        assertEquals(OutboxStatus.SENT, outboxStore.findAll().get(0).status());
        assertEquals(0, outboxStore.pendingCount());
    }

    @Test
    @DisplayName("投递失败：SENDING → PENDING，可被再次认领")
    void markFailed_shouldReturnEventToPending() {
        outboxStore.append(event("id-1", "event-1", Instant.now()));
        outboxStore.claimPending(10);

        outboxStore.markFailed("id-1");

        assertEquals(1, outboxStore.pendingCount());
        assertEquals(1, outboxStore.claimPending(10).size());
    }

    @Test
    @DisplayName("幂等注册表：首次登记返回 true，重复登记返回 false")
    void markIfAbsent_shouldRejectDuplicateEventId() {
        assertTrue(registry.markIfAbsent("event-1"));
        assertFalse(registry.markIfAbsent("event-1"));
        assertTrue(registry.isProcessed("event-1"));
        assertEquals(1, registry.size());
    }

    private static OutboxEvent event(String id, String eventId, Instant occurredOn) {
        return new OutboxEvent(id, eventId, "TestEvent", "agg-1", "{}", occurredOn);
    }
}
```

- [ ] **Step 16: 运行 starter 测试**

Run: `./mvnw -pl event-driven-spring-boot-starter test`
Expected: 全部通过；`JdbcEventDrivenStoreTest` 会启动 MySQL 容器（约 15–25 秒），若 Docker 不可用则被跳过。

- [ ] **Step 17: 提交**

```bash
git add pom.xml event-driven-spring-boot-starter
git commit -m "feat(persistence): add JDBC outbox and idempotency stores to starter"
```

---

## Task 2: order-service —— JPA 聚合 + JDBC 事件存储

**Files:**
- Modify: `demo-order-service/pom.xml`
- Add: `demo-order-service/src/main/java/com/example/demo/order/infrastructure/persistence/jpa/OrderEntity.java`
- Add: `demo-order-service/src/main/java/com/example/demo/order/infrastructure/persistence/jpa/OrderItemEntity.java`
- Add: `demo-order-service/src/main/java/com/example/demo/order/infrastructure/persistence/jpa/OrderJpaRepository.java`
- Add: `demo-order-service/src/main/java/com/example/demo/order/infrastructure/persistence/jpa/JpaOrderRepository.java`
- Add: `demo-order-service/src/main/java/com/example/demo/order/infrastructure/projection/jpa/OrderViewEntity.java`
- Add: `demo-order-service/src/main/java/com/example/demo/order/infrastructure/projection/jpa/OrderViewJpaRepository.java`
- Add: `demo-order-service/src/main/java/com/example/demo/order/infrastructure/projection/jpa/JpaOrderViewStore.java`
- Delete: `demo-order-service/src/main/java/com/example/demo/order/infrastructure/persistence/InMemoryOrderRepository.java`
- Delete: `demo-order-service/src/main/java/com/example/demo/order/infrastructure/projection/InMemoryOrderViewStore.java`
- Add: `demo-order-service/src/main/java/com/example/demo/infrastructure/event/DomainEventCodec.java`
- Modify: `demo-order-service/src/main/java/com/example/demo/infrastructure/event/DomainEventStore.java`
- Modify: `demo-order-service/src/main/java/com/example/demo/order/application/OrderApplicationService.java`
- Add: `demo-order-service/src/main/resources/db/migration/V1__order_schema.sql`
- Modify: `demo-order-service/src/main/resources/application.yaml`
- Modify: `demo-order-service/src/test/java/com/example/demo/order/OrderServiceIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 的 `JdbcOutboxStore` / `JdbcProcessedEventRegistry` / `V0` 迁移脚本
- Produces:
  - `DomainEventStore.append(DomainEvent)` / `eventsOf(String)` / `allEvents()` / `size()` / `clear()`（JDBC 实现，签名不变）
  - `DomainEventCodec.encode(DomainEvent): String` / `decode(String eventType, String payload): DomainEvent`
  - `JpaOrderRepository implements OrderRepository`、`JpaOrderViewStore implements OrderViewStore`

- [ ] **Step 1: order-service pom 增加 JPA / Flyway / MySQL / Testcontainers**

在 `demo-order-service/pom.xml` 的 `event-driven-spring-boot-starter` 依赖之后追加：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-mysql</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
```

在测试依赖 `spring-boot-starter-webmvc-test` 之后追加：

```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-mysql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: 新增 `OrderItemEntity`**

`demo-order-service/src/main/java/com/example/demo/order/infrastructure/persistence/jpa/OrderItemEntity.java`：

```java
package com.example.demo.order.infrastructure.persistence.jpa;

import com.example.demo.order.domain.model.Money;
import com.example.demo.order.domain.model.OrderItem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** 订单行持久化对象（1:1 对应 order_items）。 */
@Entity
@Table(name = "order_items")
public class OrderItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "product_id", length = 64, nullable = false)
    private String productId;

    @Column(name = "product_name", length = 128, nullable = false)
    private String productName;

    @Column(name = "unit_price", precision = 12, scale = 2, nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "currency", length = 8, nullable = false)
    private String currency;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    protected OrderItemEntity() {
    }

    static OrderItemEntity from(int lineNo, OrderItem item) {
        OrderItemEntity entity = new OrderItemEntity();
        entity.lineNo = lineNo;
        entity.productId = item.productId();
        entity.productName = item.productName();
        entity.unitPrice = item.unitPrice().amount();
        entity.currency = item.unitPrice().currency();
        entity.quantity = item.quantity();
        return entity;
    }

    OrderItem toDomain() {
        return new OrderItem(productId, productName, new Money(unitPrice, currency), quantity);
    }
}
```

- [ ] **Step 3: 新增 `OrderEntity`（含聚合映射）**

`demo-order-service/src/main/java/com/example/demo/order/infrastructure/persistence/jpa/OrderEntity.java`：

```java
package com.example.demo.order.infrastructure.persistence.jpa;

import com.example.demo.order.domain.model.Money;
import com.example.demo.order.domain.model.Order;
import com.example.demo.order.domain.model.OrderId;
import com.example.demo.order.domain.model.OrderItem;
import com.example.demo.order.domain.model.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 订单聚合的持久化对象。
 *
 * <p>领域类 {@link Order} 保持零框架注解，映射逻辑收敛在这里。
 * 订单行在聚合生命周期内不可变、数量很少，因此 EAGER 加载，避免映射发生在事务边界之外。</p>
 */
@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    @Column(name = "id", length = 32, nullable = false)
    private String id;

    @Column(name = "customer_id", length = 64, nullable = false)
    private String customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private OrderStatus status;

    @Column(name = "total_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "currency", length = 8, nullable = false)
    private String currency;

    @Column(name = "payment_id", length = 64)
    private String paymentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id", nullable = false)
    @OrderBy("lineNo ASC")
    private List<OrderItemEntity> items = new ArrayList<>();

    protected OrderEntity() {
    }

    static OrderEntity from(Order order) {
        OrderEntity entity = new OrderEntity();
        entity.id = order.id().value();
        entity.applyState(order);
        for (int index = 0; index < order.items().size(); index++) {
            entity.items.add(OrderItemEntity.from(index + 1, order.items().get(index)));
        }
        return entity;
    }

    void applyState(Order order) {
        this.customerId = order.customerId();
        this.status = order.status();
        this.totalAmount = order.totalAmount().amount();
        this.currency = order.totalAmount().currency();
        this.paymentId = order.paymentId();
        this.createdAt = order.createdAt();
        this.updatedAt = order.updatedAt();
    }

    Order toDomain() {
        List<OrderItem> domainItems = items.stream()
                .map(OrderItemEntity::toDomain)
                .toList();
        return Order.rehydrate(
                OrderId.of(id),
                customerId,
                domainItems,
                status,
                new Money(totalAmount, currency),
                paymentId,
                createdAt,
                updatedAt);
    }
}
```

- [ ] **Step 4: 新增 `OrderJpaRepository` 与 `JpaOrderRepository`**

`demo-order-service/src/main/java/com/example/demo/order/infrastructure/persistence/jpa/OrderJpaRepository.java`：

```java
package com.example.demo.order.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface OrderJpaRepository extends JpaRepository<OrderEntity, String> {

    List<OrderEntity> findAllByOrderByCreatedAtDesc();
}
```

`demo-order-service/src/main/java/com/example/demo/order/infrastructure/persistence/jpa/JpaOrderRepository.java`：

```java
package com.example.demo.order.infrastructure.persistence.jpa;

import com.example.demo.order.domain.model.Order;
import com.example.demo.order.domain.model.OrderId;
import com.example.demo.order.domain.repository.OrderRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** 订单聚合的 JPA 仓储实现。 */
@Repository
public class JpaOrderRepository implements OrderRepository {

    private final OrderJpaRepository jpaRepository;

    public JpaOrderRepository(OrderJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public void save(Order order) {
        OrderEntity entity = jpaRepository.findById(order.id().value()).orElse(null);
        if (entity == null) {
            entity = OrderEntity.from(order);
        } else {
            entity.applyState(order);
        }
        jpaRepository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Order> findById(OrderId id) {
        return jpaRepository.findById(id.value()).map(OrderEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> findAll() {
        return jpaRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(OrderEntity::toDomain)
                .toList();
    }
}
```

- [ ] **Step 5: 新增读模型 PO / 仓储 / 存储实现**

`demo-order-service/src/main/java/com/example/demo/order/infrastructure/projection/jpa/OrderViewEntity.java`：

```java
package com.example.demo.order.infrastructure.projection.jpa;

import com.example.demo.order.application.query.OrderView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/** 订单读模型持久化对象（1:1 对应 order_view）。 */
@Entity
@Table(name = "order_view")
public class OrderViewEntity {

    @Id
    @Column(name = "order_id", length = 32, nullable = false)
    private String orderId;

    @Column(name = "customer_id", length = 64, nullable = false)
    private String customerId;

    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "status_desc", length = 32, nullable = false)
    private String statusDesc;

    @Column(name = "total_amount", precision = 12, scale = 2, nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "currency", length = 8, nullable = false)
    private String currency;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OrderViewEntity() {
    }

    static OrderViewEntity from(OrderView view) {
        OrderViewEntity entity = new OrderViewEntity();
        entity.orderId = view.orderId();
        entity.customerId = view.customerId();
        entity.status = view.status();
        entity.statusDesc = view.statusDesc();
        entity.totalAmount = view.totalAmount();
        entity.currency = view.currency();
        entity.itemCount = view.itemCount();
        entity.createdAt = view.createdAt();
        entity.updatedAt = view.updatedAt();
        return entity;
    }

    OrderView toView() {
        return new OrderView(orderId, customerId, status, statusDesc,
                totalAmount, currency, itemCount, createdAt, updatedAt);
    }
}
```

`demo-order-service/src/main/java/com/example/demo/order/infrastructure/projection/jpa/OrderViewJpaRepository.java`：

```java
package com.example.demo.order.infrastructure.projection.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface OrderViewJpaRepository extends JpaRepository<OrderViewEntity, String> {

    List<OrderViewEntity> findAllByOrderByCreatedAtDesc();
}
```

`demo-order-service/src/main/java/com/example/demo/order/infrastructure/projection/jpa/JpaOrderViewStore.java`：

```java
package com.example.demo.order.infrastructure.projection.jpa;

import com.example.demo.order.application.query.OrderView;
import com.example.demo.order.application.query.OrderViewStore;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 订单读模型的 JPA 存储实现。 */
@Repository
public class JpaOrderViewStore implements OrderViewStore {

    private final OrderViewJpaRepository jpaRepository;

    public JpaOrderViewStore(OrderViewJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(OrderView view) {
        jpaRepository.save(OrderViewEntity.from(view));
    }

    @Override
    public Optional<OrderView> findById(String orderId) {
        return jpaRepository.findById(orderId).map(OrderViewEntity::toView);
    }

    @Override
    public List<OrderView> findAll() {
        return jpaRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(OrderViewEntity::toView)
                .toList();
    }

    @Override
    public void clear() {
        jpaRepository.deleteAllInBatch();
    }
}
```

- [ ] **Step 6: 删除内存实现**

```bash
rm demo-order-service/src/main/java/com/example/demo/order/infrastructure/persistence/InMemoryOrderRepository.java
rm demo-order-service/src/main/java/com/example/demo/order/infrastructure/projection/InMemoryOrderViewStore.java
```

- [ ] **Step 7: 新增 `DomainEventCodec`**

`demo-order-service/src/main/java/com/example/demo/infrastructure/event/DomainEventCodec.java`：

```java
package com.example.demo.infrastructure.event;

import com.example.demo.eventdriven.domain.DomainEvent;
import com.example.demo.order.domain.event.InventoryReservationFailedEvent;
import com.example.demo.order.domain.event.OrderCancelledEvent;
import com.example.demo.order.domain.event.OrderConfirmedEvent;
import com.example.demo.order.domain.event.OrderCreatedEvent;
import com.example.demo.order.domain.event.OrderPaidEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 领域事件的 JSON 编解码：eventType → 具体事件类。 */
@Component
public class DomainEventCodec {

    private final ObjectMapper objectMapper;
    private final Map<String, Class<? extends DomainEvent>> eventTypes = Map.of(
            "OrderCreatedEvent", OrderCreatedEvent.class,
            "OrderConfirmedEvent", OrderConfirmedEvent.class,
            "OrderPaidEvent", OrderPaidEvent.class,
            "OrderCancelledEvent", OrderCancelledEvent.class,
            "InventoryReservationFailedEvent", InventoryReservationFailedEvent.class);

    public DomainEventCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("领域事件序列化失败: " + event.eventType(), e);
        }
    }

    public DomainEvent decode(String eventType, String payload) {
        Class<? extends DomainEvent> type = eventTypes.get(eventType);
        if (type == null) {
            throw new IllegalArgumentException("未知事件类型: " + eventType);
        }
        try {
            return objectMapper.readValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("领域事件反序列化失败: " + eventType, e);
        }
    }
}
```

- [ ] **Step 8: 重写 `DomainEventStore` 为 JDBC 实现**

`demo-order-service/src/main/java/com/example/demo/infrastructure/event/DomainEventStore.java`：

```java
package com.example.demo.infrastructure.event;

import com.example.demo.eventdriven.domain.DomainEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;

/**
 * 领域事件存储（MySQL 版）。
 *
 * <p>用于审计、排障和事件溯源；与 Outbox 不同，它只做本地留存，不参与投递。</p>
 */
@Component
public class DomainEventStore {

    private final JdbcTemplate jdbcTemplate;
    private final DomainEventCodec codec;
    private final RowMapper<DomainEvent> rowMapper;

    public DomainEventStore(JdbcTemplate jdbcTemplate, DomainEventCodec codec) {
        this.jdbcTemplate = jdbcTemplate;
        this.codec = codec;
        this.rowMapper = (rs, rowNum) -> codec.decode(rs.getString("event_type"), rs.getString("payload"));
    }

    /**
     * 存储事件（参与调用方事务）。
     */
    public void append(DomainEvent event) {
        jdbcTemplate.update(
                "INSERT INTO domain_event (event_id, event_type, aggregate_id, payload, occurred_on) "
                        + "VALUES (?, ?, ?, ?, ?)",
                event.eventId(),
                event.eventType(),
                event.aggregateId(),
                codec.encode(event),
                Timestamp.from(event.occurredOn()));
    }

    /**
     * 获取指定聚合根的所有事件（按写入顺序）。
     */
    public List<DomainEvent> eventsOf(String aggregateId) {
        return jdbcTemplate.query(
                "SELECT event_type, payload FROM domain_event WHERE aggregate_id = ? ORDER BY seq",
                rowMapper, aggregateId);
    }

    /**
     * 获取所有事件（按写入顺序）。
     */
    public List<DomainEvent> findAll() {
        return jdbcTemplate.query(
                "SELECT event_type, payload FROM domain_event ORDER BY seq", rowMapper);
    }

    /**
     * 获取所有事件（别名方法）。
     */
    public List<DomainEvent> allEvents() {
        return findAll();
    }

    /**
     * 获取事件总数。
     */
    public int size() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM domain_event", Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 清空所有事件（仅测试用）。
     */
    public void clear() {
        jdbcTemplate.update("DELETE FROM domain_event");
    }
}
```

- [ ] **Step 9: 给应用服务加 `@Transactional`**

`demo-order-service/src/main/java/com/example/demo/order/application/OrderApplicationService.java`：

1. 增加 import：

```java
import org.springframework.transaction.annotation.Transactional;
```

2. 在 `placeOrder`、`confirmOrder`、`payOrder`、`cancelOrder` 四个方法上各加一行 `@Transactional`（放在方法签名上一行）。例如：

```java
    /** 用例：下单。 */
    @Transactional
    public OrderId placeOrder(PlaceOrderCommand command) {
```

其余方法体不变。

- [ ] **Step 10: 新增 order Flyway 脚本**

`demo-order-service/src/main/resources/db/migration/V1__order_schema.sql`：

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE domain_event (
    seq          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id     VARCHAR(64)   NOT NULL,
    event_type   VARCHAR(64)   NOT NULL,
    aggregate_id VARCHAR(64)   NOT NULL,
    payload      LONGTEXT      NOT NULL,
    occurred_on  DATETIME(6)   NOT NULL,
    KEY idx_domain_event_aggregate (aggregate_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 11: 更新 order `application.yaml`**

`demo-order-service/src/main/resources/application.yaml` 完整替换：

```yaml
spring:
  application:
    name: demo-order-service
  datasource:
    url: jdbc:mysql://localhost:3306/demo_order?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&characterEncoding=utf8
    username: root
    password: ${MYSQL_PASSWORD:123456}
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: none
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration,classpath:db/event-driven
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest

server:
  port: 9001

eventdriven:
  persistence: jdbc
  rabbitmq:
    queue: ${spring.application.name}.queue

logging:
  level:
    root: INFO
    com.example.demo: INFO
  pattern:
    console: "%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n"
```

- [ ] **Step 12: 改造订单集成测试（Testcontainers）**

`demo-order-service/src/test/java/com/example/demo/order/OrderServiceIntegrationTest.java`：

1. 删除对 `InMemoryOrderRepository` 的 import 与字段。
2. 增加 import：

```java
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
```

3. 类注解改为：

```java
@SpringBootTest(properties = {
        "eventdriven.outbox.relay-enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
@Testcontainers(disabledWithoutDocker = true)
class OrderServiceIntegrationTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("demo_order")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
```

4. 删除 `@Autowired private InMemoryOrderRepository orderRepository;`，增加：

```java
    @Autowired
    private JdbcTemplate jdbcTemplate;
```

5. `resetAll()` 改为：

```java
    @BeforeEach
    void resetAll() {
        jdbcTemplate.update("DELETE FROM order_items");
        jdbcTemplate.update("DELETE FROM orders");
        jdbcTemplate.update("DELETE FROM order_view");
        jdbcTemplate.update("DELETE FROM domain_event");
        jdbcTemplate.update("DELETE FROM outbox_event");
        jdbcTemplate.update("DELETE FROM processed_event");
    }
```

6. 在类末尾新增一个持久化断言测试（放在 `placeOrderValidation` 之前）：

```java
    @Test
    @DisplayName("持久化：聚合、明细、读模型、事件流、Outbox 全部落库")
    void persistsAllStateInMySql() {
        OrderId orderId = orderService.placeOrder(
                command("C-7007", line("P-1001", "机械键盘", "399.00", 2)));

        assertEquals("CREATED", jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId.value()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_items WHERE order_id = ?", Integer.class, orderId.value()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_view WHERE order_id = ?", Integer.class, orderId.value()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM domain_event WHERE aggregate_id = ?", Integer.class, orderId.value()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event", Integer.class));

        // 聚合能从库里重建并继续流转
        orderService.confirmOrder(orderId.value());
        assertEquals("CONFIRMED", jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId.value()));
    }
```

其余测试方法体保持不变。

- [ ] **Step 13: 运行 order 模块测试**

Run: `./mvnw -pl event-driven-spring-boot-starter install -DskipTests && ./mvnw -pl demo-order-service test`
Expected: 5 个原测试 + 新增持久化测试全部通过。

- [ ] **Step 14: 提交**

```bash
git add demo-order-service
git commit -m "feat(persistence): persist orders, read model and event store in MySQL"
```

---

## Task 3: inventory-service —— 库存悲观锁持久化

**Files:**
- Modify: `demo-inventory-service/pom.xml`
- Add: `demo-inventory-service/src/main/java/com/example/demo/inventory/domain/Stock.java`
- Add: `demo-inventory-service/src/main/java/com/example/demo/inventory/domain/StockRepository.java`
- Add: `demo-inventory-service/src/main/java/com/example/demo/inventory/infrastructure/persistence/StockEntity.java`
- Add: `demo-inventory-service/src/main/java/com/example/demo/inventory/infrastructure/persistence/StockJpaRepository.java`
- Add: `demo-inventory-service/src/main/java/com/example/demo/inventory/infrastructure/persistence/JpaStockRepository.java`
- Modify: `demo-inventory-service/src/main/java/com/example/demo/inventory/InventoryService.java`
- Add: `demo-inventory-service/src/main/resources/db/migration/V1__inventory_schema.sql`
- Modify: `demo-inventory-service/src/main/resources/application.yaml`
- Modify: `demo-inventory-service/src/test/java/com/example/demo/inventory/InventoryEventListenerTest.java`
- Add: `demo-inventory-service/src/test/java/com/example/demo/inventory/InventoryPersistenceTest.java`

**Interfaces:**
- Consumes: `event-driven-spring-boot-starter` 的 JDBC 持久化（`eventdriven.persistence=jdbc`）
- Produces:
  - `Stock(String productId, int available, int reserved)`，`canReserve(int)` / `reserve(int)` / `release(int)`
  - `StockRepository.findById(String)` / `findByIdForUpdate(String)` / `save(Stock)` / `findAll()`
  - `InventoryService` 构造器变为 `InventoryService(StockRepository)`，方法签名不变

- [ ] **Step 1: inventory pom 增加 JPA / Flyway / MySQL / Testcontainers**

在 `demo-inventory-service/pom.xml` 的 starter 依赖之后追加：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-mysql</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
```

测试依赖追加：

```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-mysql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: 新增领域值 `Stock` 与端口 `StockRepository`**

`demo-inventory-service/src/main/java/com/example/demo/inventory/domain/Stock.java`：

```java
package com.example.demo.inventory.domain;

/**
 * 单个商品的库存快照。
 *
 * <p>available + reserved 的变更都通过值对象方法生成新快照，避免散落的加减逻辑。</p>
 */
public record Stock(String productId, int available, int reserved) {

    public Stock {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("商品 ID 不能为空");
        }
        if (available < 0 || reserved < 0) {
            throw new IllegalArgumentException("库存数量不能为负数");
        }
    }

    public boolean canReserve(int quantity) {
        return available >= quantity;
    }

    public Stock reserve(int quantity) {
        return new Stock(productId, available - quantity, reserved + quantity);
    }

    /** 释放数量以实际预占为上限，避免未预占成功的明细虚增库存。 */
    public Stock release(int quantity) {
        int actual = Math.min(quantity, reserved);
        return new Stock(productId, available + actual, reserved - actual);
    }
}
```

`demo-inventory-service/src/main/java/com/example/demo/inventory/domain/StockRepository.java`：

```java
package com.example.demo.inventory.domain;

import java.util.List;
import java.util.Optional;

/** 库存仓储端口。 */
public interface StockRepository {

    Optional<Stock> findById(String productId);

    /** 加行锁读取，用于预占/释放。 */
    Optional<Stock> findByIdForUpdate(String productId);

    void save(Stock stock);

    List<Stock> findAll();
}
```

- [ ] **Step 3: 新增 JPA 实体与仓储实现**

`demo-inventory-service/src/main/java/com/example/demo/inventory/infrastructure/persistence/StockEntity.java`：

```java
package com.example.demo.inventory.infrastructure.persistence;

import com.example.demo.inventory.domain.Stock;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** 库存持久化对象（1:1 对应 inventory_stock）。 */
@Entity
@Table(name = "inventory_stock")
public class StockEntity {

    @Id
    @Column(name = "product_id", length = 64, nullable = false)
    private String productId;

    @Column(name = "available", nullable = false)
    private int available;

    @Column(name = "reserved", nullable = false)
    private int reserved;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StockEntity() {
    }

    void apply(Stock stock) {
        this.available = stock.available();
        this.reserved = stock.reserved();
        this.updatedAt = Instant.now();
    }

    Stock toDomain() {
        return new Stock(productId, available, reserved);
    }
}
```

`demo-inventory-service/src/main/java/com/example/demo/inventory/infrastructure/persistence/StockJpaRepository.java`：

```java
package com.example.demo.inventory.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

interface StockJpaRepository extends JpaRepository<StockEntity, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockEntity s where s.productId = :productId")
    Optional<StockEntity> findForUpdate(@Param("productId") String productId);
}
```

`demo-inventory-service/src/main/java/com/example/demo/inventory/infrastructure/persistence/JpaStockRepository.java`：

```java
package com.example.demo.inventory.infrastructure.persistence;

import com.example.demo.inventory.domain.Stock;
import com.example.demo.inventory.domain.StockRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** 库存仓储的 JPA 实现：写路径用悲观行锁串行化同一商品。 */
@Repository
public class JpaStockRepository implements StockRepository {

    private final StockJpaRepository jpaRepository;

    public JpaStockRepository(StockJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Stock> findById(String productId) {
        return jpaRepository.findById(productId).map(StockEntity::toDomain);
    }

    @Override
    @Transactional
    public Optional<Stock> findByIdForUpdate(String productId) {
        return jpaRepository.findForUpdate(productId).map(StockEntity::toDomain);
    }

    @Override
    @Transactional
    public void save(Stock stock) {
        StockEntity entity = jpaRepository.findById(stock.productId()).orElseThrow(
                () -> new IllegalArgumentException("库存记录不存在: " + stock.productId()));
        entity.apply(stock);
        jpaRepository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Stock> findAll() {
        return jpaRepository.findAll().stream()
                .map(StockEntity::toDomain)
                .toList();
    }
}
```

- [ ] **Step 4: 重写 `InventoryService`**

`demo-inventory-service/src/main/java/com/example/demo/inventory/InventoryService.java`：

```java
package com.example.demo.inventory;

import com.example.demo.inventory.domain.Stock;
import com.example.demo.inventory.domain.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.TreeMap;

/**
 * 库存服务（库存限界上下文的核心）。
 *
 * <p>它与订单上下文只有「事件契约」这一条耦合：
 * 订单不知道库存存在，库存也不知道订单的内部模型。</p>
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final StockRepository stockRepository;

    public InventoryService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    /** 预占库存，成功返回 true。 */
    @Transactional
    public boolean reserve(String productId, int quantity) {
        Stock stock = stockRepository.findByIdForUpdate(productId).orElse(null);
        if (stock == null) {
            log.warn("[库存] 预占失败，商品不存在 productId={}", productId);
            return false;
        }
        if (!stock.canReserve(quantity)) {
            log.warn("[库存] 预占失败，库存不足 productId={} 需要={} 可用={}",
                    productId, quantity, stock.available());
            return false;
        }
        stockRepository.save(stock.reserve(quantity));
        log.info("[库存] 预占成功 productId={} 数量={} 剩余={}",
                productId, quantity, stock.available() - quantity);
        return true;
    }

    /** 释放库存（订单取消时补偿）。只释放实际预占过的数量，避免未预占成功的明细虚增库存。 */
    @Transactional
    public void release(String productId, int quantity) {
        Stock stock = stockRepository.findByIdForUpdate(productId).orElse(null);
        if (stock == null) {
            log.warn("[库存] 忽略释放：商品不存在 productId={}", productId);
            return;
        }
        Stock released = stock.release(quantity);
        if (released.reserved() == stock.reserved()) {
            log.warn("[库存] 忽略释放：该商品无预占记录 productId={} 请求释放={}", productId, quantity);
            return;
        }
        stockRepository.save(released);
        log.info("[库存] 已释放 productId={} 数量={} 当前可用={}",
                productId, stock.reserved() - released.reserved(), released.available());
    }

    @Transactional(readOnly = true)
    public int available(String productId) {
        return stockRepository.findById(productId).map(Stock::available).orElse(0);
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> stockSnapshot() {
        Map<String, Integer> snapshot = new TreeMap<>();
        stockRepository.findAll().forEach(stock -> snapshot.put(stock.productId(), stock.available()));
        return snapshot;
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> reservedSnapshot() {
        Map<String, Integer> snapshot = new TreeMap<>();
        stockRepository.findAll().forEach(stock -> snapshot.put(stock.productId(), stock.reserved()));
        return snapshot;
    }
}
```

- [ ] **Step 5: 新增库存 Flyway 脚本（含种子数据）**

`demo-inventory-service/src/main/resources/db/migration/V1__inventory_schema.sql`：

```sql
CREATE TABLE inventory_stock (
    product_id VARCHAR(64) NOT NULL PRIMARY KEY,
    available  INT         NOT NULL,
    reserved   INT         NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO inventory_stock (product_id, available, reserved)
VALUES ('P-1001', 100, 0), ('P-1002', 50, 0), ('P-1003', 20, 0);
```

- [ ] **Step 6: 更新 inventory `application.yaml`**

`demo-inventory-service/src/main/resources/application.yaml` 完整替换：

```yaml
spring:
  application:
    name: demo-inventory-service
  datasource:
    url: jdbc:mysql://localhost:3306/demo_inventory?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&characterEncoding=utf8
    username: root
    password: ${MYSQL_PASSWORD:123456}
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: none
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration,classpath:db/event-driven
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest

server:
  port: 9002

eventdriven:
  persistence: jdbc
  rabbitmq:
    queue: ${spring.application.name}.queue

logging:
  level:
    root: INFO
    com.example.demo: INFO
  pattern:
    console: "%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n"
```

- [ ] **Step 7: 单元测试改用内存假仓储**

`demo-inventory-service/src/test/java/com/example/demo/inventory/InventoryEventListenerTest.java`：

1. 在 `setUp()` 中改成：

```java
        inventoryService = new InventoryService(new InMemoryStockRepository());
```

2. 在测试类末尾追加假仓储：

```java
    private static final class InMemoryStockRepository implements StockRepository {

        private final Map<String, Stock> stocks = new ConcurrentHashMap<>();

        InMemoryStockRepository() {
            stocks.put("P-1001", new Stock("P-1001", 100, 0));
            stocks.put("P-1002", new Stock("P-1002", 50, 0));
            stocks.put("P-1003", new Stock("P-1003", 20, 0));
        }

        @Override
        public Optional<Stock> findById(String productId) {
            return Optional.ofNullable(stocks.get(productId));
        }

        @Override
        public Optional<Stock> findByIdForUpdate(String productId) {
            return findById(productId);
        }

        @Override
        public void save(Stock stock) {
            stocks.put(stock.productId(), stock);
        }

        @Override
        public List<Stock> findAll() {
            return List.copyOf(stocks.values());
        }
    }
```

3. 补充 import：

```java
import com.example.demo.inventory.domain.Stock;
import com.example.demo.inventory.domain.StockRepository;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
```

- [ ] **Step 8: 新增库存持久化测试**

`demo-inventory-service/src/test/java/com/example/demo/inventory/InventoryPersistenceTest.java`：

```java
package com.example.demo.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eventdriven.outbox.relay-enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class InventoryPersistenceTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("demo_inventory")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("预占：available 减少、reserved 增加，并落库")
    void reserve_shouldPersistAvailableAndReserved() {
        assertTrue(inventoryService.reserve("P-1001", 3));

        assertEquals(97, jdbcTemplate.queryForObject(
                "SELECT available FROM inventory_stock WHERE product_id = 'P-1001'", Integer.class));
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT reserved FROM inventory_stock WHERE product_id = 'P-1001'", Integer.class));
    }

    @Test
    @DisplayName("释放：按预占量归还，且不会超过预占")
    void release_shouldReturnReservedStock() {
        assertTrue(inventoryService.reserve("P-1002", 5));

        inventoryService.release("P-1002", 5);
        assertEquals(50, jdbcTemplate.queryForObject(
                "SELECT available FROM inventory_stock WHERE product_id = 'P-1002'", Integer.class));

        inventoryService.release("P-1002", 5);
        assertEquals(50, jdbcTemplate.queryForObject(
                "SELECT available FROM inventory_stock WHERE product_id = 'P-1002'", Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT reserved FROM inventory_stock WHERE product_id = 'P-1002'", Integer.class));
    }

    @Test
    @DisplayName("库存不足：预占失败且不改动数据")
    void reserve_shouldFailWhenInsufficient() {
        assertFalse(inventoryService.reserve("P-1003", 25));

        assertEquals(20, jdbcTemplate.queryForObject(
                "SELECT available FROM inventory_stock WHERE product_id = 'P-1003'", Integer.class));
    }
}
```

- [ ] **Step 9: 运行 inventory 模块测试**

Run: `./mvnw -pl event-driven-spring-boot-starter install -DskipTests && ./mvnw -pl demo-inventory-service test`
Expected: 5 个单元测试 + 3 个持久化测试全部通过。

- [ ] **Step 10: 提交**

```bash
git add demo-inventory-service
git commit -m "feat(persistence): persist inventory stock with pessimistic locking"
```

---

## Task 4: loyalty-service —— 原子累加持久化

**Files:**
- Modify: `demo-loyalty-service/pom.xml`
- Add: `demo-loyalty-service/src/main/java/com/example/demo/loyalty/domain/PointsRepository.java`
- Add: `demo-loyalty-service/src/main/java/com/example/demo/loyalty/infrastructure/persistence/JdbcPointsRepository.java`
- Modify: `demo-loyalty-service/src/main/java/com/example/demo/loyalty/LoyaltyPointsService.java`
- Add: `demo-loyalty-service/src/main/resources/db/migration/V1__loyalty_schema.sql`
- Modify: `demo-loyalty-service/src/main/resources/application.yaml`
- Modify: `demo-loyalty-service/src/test/java/com/example/demo/loyalty/LoyaltyEventListenerTest.java`
- Add: `demo-loyalty-service/src/test/java/com/example/demo/loyalty/LoyaltyPersistenceTest.java`

**Interfaces:**
- Consumes: `event-driven-spring-boot-starter` 的 JDBC 持久化（`eventdriven.persistence=jdbc`）
- Produces:
  - `PointsRepository.add(String customerId, int points): int`（返回累计值）
  - `PointsRepository.pointsOf(String customerId): int`
  - `PointsRepository.findAll(): Map<String, Integer>`
  - `LoyaltyPointsService` 构造器变为 `LoyaltyPointsService(PointsRepository)`，方法签名不变

- [ ] **Step 1: loyalty pom 增加 JDBC / Flyway / MySQL / Testcontainers**

loyalty 不使用 JPA，`spring-boot-starter-flyway` 已传递 `spring-boot-starter-jdbc`。在 starter 依赖之后追加：

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-flyway</artifactId>
        </dependency>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-mysql</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
```

测试依赖追加：

```xml
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-mysql</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>testcontainers-junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: 新增 `PointsRepository` 端口**

`demo-loyalty-service/src/main/java/com/example/demo/loyalty/domain/PointsRepository.java`：

```java
package com.example.demo.loyalty.domain;

import java.util.Map;

/** 积分仓储端口。 */
public interface PointsRepository {

    /** 累加积分并返回最新累计值（单条 SQL 原子完成）。 */
    int add(String customerId, int points);

    int pointsOf(String customerId);

    Map<String, Integer> findAll();
}
```

- [ ] **Step 3: 新增 `JdbcPointsRepository`**

`demo-loyalty-service/src/main/java/com/example/demo/loyalty/infrastructure/persistence/JdbcPointsRepository.java`：

```java
package com.example.demo.loyalty.infrastructure.persistence;

import com.example.demo.loyalty.domain.PointsRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.TreeMap;

/**
 * 积分仓储的 JDBC 实现。
 *
 * <p>用 ON DUPLICATE KEY UPDATE 做单条 SQL 原子累加，避免「先查后写」的并发丢更新。</p>
 */
@Repository
public class JdbcPointsRepository implements PointsRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcPointsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int add(String customerId, int points) {
        jdbcTemplate.update(
                "INSERT INTO loyalty_points (customer_id, points) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE points = loyalty_points.points + ?",
                customerId, points, points);
        return pointsOf(customerId);
    }

    @Override
    public int pointsOf(String customerId) {
        Integer points = jdbcTemplate.query(
                "SELECT points FROM loyalty_points WHERE customer_id = ?",
                rs -> rs.next() ? rs.getInt(1) : 0,
                customerId);
        return points == null ? 0 : points;
    }

    @Override
    public Map<String, Integer> findAll() {
        Map<String, Integer> snapshot = new TreeMap<>();
        jdbcTemplate.query("SELECT customer_id, points FROM loyalty_points",
                rs -> {
                    snapshot.put(rs.getString("customer_id"), rs.getInt("points"));
                });
        return snapshot;
    }
}
```

- [ ] **Step 4: 重写 `LoyaltyPointsService`**

`demo-loyalty-service/src/main/java/com/example/demo/loyalty/LoyaltyPointsService.java`：

```java
package com.example.demo.loyalty;

import com.example.demo.loyalty.domain.PointsRepository;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 积分服务（独立限界上下文）。
 *
 * <p>它只订阅 OrderPaidEvent —— 订阅者只关心自己需要的最小事件，
 * 上游新增事件时不会波及下游。</p>
 */
@Service
public class LoyaltyPointsService {

    private final PointsRepository pointsRepository;

    public LoyaltyPointsService(PointsRepository pointsRepository) {
        this.pointsRepository = pointsRepository;
    }

    /** 发放积分，返回最新累计值。 */
    public int earn(String customerId, int pointsToEarn) {
        return pointsRepository.add(customerId, pointsToEarn);
    }

    public int pointsOf(String customerId) {
        return pointsRepository.pointsOf(customerId);
    }

    public Map<String, Integer> snapshot() {
        return pointsRepository.findAll();
    }
}
```

- [ ] **Step 5: 新增积分 Flyway 脚本**

`demo-loyalty-service/src/main/resources/db/migration/V1__loyalty_schema.sql`：

```sql
CREATE TABLE loyalty_points (
    customer_id VARCHAR(64) NOT NULL PRIMARY KEY,
    points      INT         NOT NULL,
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 6: 更新 loyalty `application.yaml`**

`demo-loyalty-service/src/main/resources/application.yaml` 完整替换：

```yaml
spring:
  application:
    name: demo-loyalty-service
  datasource:
    url: jdbc:mysql://localhost:3306/demo_loyalty?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC&characterEncoding=utf8
    username: root
    password: ${MYSQL_PASSWORD:123456}
  flyway:
    enabled: true
    locations: classpath:db/migration,classpath:db/event-driven
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest

server:
  port: 9003

eventdriven:
  persistence: jdbc
  rabbitmq:
    queue: ${spring.application.name}.queue

logging:
  level:
    root: INFO
    com.example.demo: INFO
  pattern:
    console: "%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n"
```

- [ ] **Step 7: 单元测试改用内存假仓储**

`demo-loyalty-service/src/test/java/com/example/demo/loyalty/LoyaltyEventListenerTest.java`：

1. `setUp()` 改为：

```java
        loyaltyPointsService = new LoyaltyPointsService(new InMemoryPointsRepository());
```

2. 追加假仓储：

```java
    private static final class InMemoryPointsRepository implements PointsRepository {

        private final Map<String, Integer> points = new ConcurrentHashMap<>();

        @Override
        public int add(String customerId, int pointsToAdd) {
            return points.merge(customerId, pointsToAdd, Integer::sum);
        }

        @Override
        public int pointsOf(String customerId) {
            return points.getOrDefault(customerId, 0);
        }

        @Override
        public Map<String, Integer> findAll() {
            return new TreeMap<>(points);
        }
    }
```

3. 补充 import：

```java
import com.example.demo.loyalty.domain.PointsRepository;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
```

- [ ] **Step 8: 新增积分持久化测试**

`demo-loyalty-service/src/test/java/com/example/demo/loyalty/LoyaltyPersistenceTest.java`：

```java
package com.example.demo.loyalty;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eventdriven.outbox.relay-enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false"
})
class LoyaltyPersistenceTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("demo_loyalty")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private LoyaltyPointsService loyaltyPointsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("积分累加：同一客户多次发放落库并累加")
    void earn_shouldAccumulateAndPersist() {
        assertEquals(79, loyaltyPointsService.earn("C-PERSIST-1", 79));
        assertEquals(89, loyaltyPointsService.earn("C-PERSIST-1", 10));

        assertEquals(89, jdbcTemplate.queryForObject(
                "SELECT points FROM loyalty_points WHERE customer_id = 'C-PERSIST-1'", Integer.class));
    }

    @Test
    @DisplayName("积分按客户隔离")
    void pointsArePerCustomer() {
        loyaltyPointsService.earn("C-PERSIST-2", 5);
        loyaltyPointsService.earn("C-PERSIST-3", 2);

        assertEquals(5, loyaltyPointsService.pointsOf("C-PERSIST-2"));
        assertEquals(2, loyaltyPointsService.pointsOf("C-PERSIST-3"));
        assertEquals(0, loyaltyPointsService.pointsOf("C-PERSIST-404"));
    }
}
```

- [ ] **Step 9: 运行 loyalty 模块测试**

Run: `./mvnw -pl event-driven-spring-boot-starter install -DskipTests && ./mvnw -pl demo-loyalty-service test`
Expected: 3 个单元测试 + 2 个持久化测试全部通过。

- [ ] **Step 10: 提交**

```bash
git add demo-loyalty-service
git commit -m "feat(persistence): persist loyalty points with atomic upsert"
```

---

## Task 5: 文档、全量构建与端到端验收

**Files:**
- Modify: `README.md`
- Modify: `event-driven-spring-boot-starter/README.md`
- Modify: `docs/index.html`（把 `outboxStore.pending()` 片段改为 `claimPending(...)` + `markDelivered(...)`）

**Interfaces:**
- Consumes: Task 1–4 的全部实现
- Produces: 可直接照做的启动/验收说明

- [ ] **Step 1: 更新 starter README 配置表与持久化说明**

在 `event-driven-spring-boot-starter/README.md` 的配置表追加两行：

```markdown
| `eventdriven.persistence` | `memory` | `memory` / `jdbc`；`jdbc` 需容器里有 `DataSource` |
| `eventdriven.outbox.claim-batch-size` | `100` | 每轮认领的最大事件数 |
```

把「生产环境建议 → 1. 替换 OutboxStore 为数据库实现」整段替换为：

```markdown
### 1. 使用内置的 JDBC 实现

```yaml
eventdriven:
  persistence: jdbc
```

`jdbc` 模式要求：
- 容器里存在 `DataSource` / `JdbcTemplate`（引入 `spring-boot-starter-jdbc` 或 JPA starter）；
- 数据库已执行 `db/event-driven/V0__event_driven_infrastructure.sql`
  （用 Flyway 时把 `classpath:db/event-driven` 加入 locations）。

Outbox 表的认领使用 `SELECT ... FOR UPDATE SKIP LOCKED`，幂等表使用 `INSERT IGNORE`。
```

- [ ] **Step 2: 更新主 README**

在 `README.md` 的「快速开始」之后增加一节：

```markdown
## MySQL 持久化

三个服务默认连接本机 MySQL（`localhost:3306`，root/`123456`，可用 `MYSQL_PASSWORD` 覆盖）：

| 服务 | 数据库 | 表 |
| --- | --- | --- |
| demo-order-service | `demo_order` | `orders` / `order_items` / `order_view` / `domain_event` + starter 的 `outbox_event` / `processed_event` |
| demo-inventory-service | `demo_inventory` | `inventory_stock` + starter 表 |
| demo-loyalty-service | `demo_loyalty` | `loyalty_points` + starter 表 |

```bash
docker compose up -d mysql
./mvnw clean install
# 分别启动三个服务；首次启动由 Flyway 建表并写入库存种子数据
```

- `eventdriven.persistence=jdbc` 时 Outbox / 幂等记录落库，重启不丢；
- 业务表由 Flyway 管理，`ddl-auto: none`；
- 集成测试使用 Testcontainers（需要 Docker，本机已有 `mysql:8.4` 镜像）。
```

- [ ] **Step 3: 更新 `docs/index.html` 中的 Outbox 代码片段**

把片段中的 `outboxStore.pending()` 与 `event.markDelivered()` 改为：

```java
List<OutboxEvent> batch = outboxStore.claimPending(properties.getOutbox().getClaimBatchSize());
for (OutboxEvent event : batch) {
    rabbitTemplate.convertAndSend(exchange, routingKey, message);
    outboxStore.markDelivered(event.id());
}
```

- [ ] **Step 4: 全量构建**

Run: `./mvnw clean install`
Expected: 所有模块 BUILD SUCCESS，测试全绿（含 3 个服务的 Testcontainers 测试与 starter 的 JDBC 测试）。

- [ ] **Step 5: 端到端手工验收**

```bash
export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"
docker compose up -d mysql rabbitmq
./mvnw -pl demo-order-service spring-boot:run        # 终端 1
./mvnw -pl demo-inventory-service spring-boot:run    # 终端 2
./mvnw -pl demo-loyalty-service spring-boot:run      # 终端 3
```

正常流：

```bash
curl -s -X POST http://localhost:9001/api/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"C-9001","items":[{"productId":"P-1001","productName":"机械键盘","unitPrice":399.00,"quantity":2}]}'
# 记下 orderId
curl -s -X POST http://localhost:9001/api/orders/{orderId}/pay -H 'Content-Type: application/json' -d '{"paymentId":"PAY-MYSQL-001"}'
curl -s http://localhost:9002/api/inventory/stock      # P-1001 = 98
curl -s http://localhost:9003/api/loyalty/points/C-9001  # 79
```

补偿流（库存不足，订单取消、库存不变）：

```bash
curl -s -X POST http://localhost:9001/api/orders -H 'Content-Type: application/json' \
  -d '{"customerId":"C-9002","items":[{"productId":"P-1003","productName":"显示器支架","unitPrice":199.00,"quantity":25}]}'
# 等待 2 秒后查订单状态应为 CANCELLED，P-1003 仍为 20
```

重启持久化验证：

```bash
# Ctrl+C 停掉三个服务后重新启动，再查：
curl -s http://localhost:9001/api/orders        # 订单仍在
curl -s http://localhost:9002/api/inventory/stock
curl -s http://localhost:9003/api/loyalty/points/C-9001
```

- [ ] **Step 6: 提交**

```bash
git add README.md event-driven-spring-boot-starter/README.md docs/index.html
git commit -m "docs(persistence): document MySQL setup and verification steps"
```

---

## Self-Review

- **Spec coverage:** 决策表（三服务全持久化 / 三库 / JPA+JdbcTemplate / Flyway / Testcontainers）→ Task 1–5；starter 三方法 + `persistence` + `claim-batch-size` + V0 → Task 1；order 的 JPA/读模型/JDBC 事件存储/@Transactional/删除内存实现/V1 → Task 2；inventory 的 Stock/端口/悲观锁/@Transactional/种子数据 → Task 3；loyalty 的端口/原子 upsert → Task 4；测试与手工验收 → 各任务 + Task 5。
- **Placeholder scan:** 无 TBD/TODO；所有新增文件均给出完整代码。
- **Type consistency:** `claimPending(int)`、`markDelivered(String)`、`markFailed(String)` 在 Task 1 定义、Task 1 测试与 `OutboxRelay` 使用；`StockRepository.findByIdForUpdate` 在 Task 3 定义与使用；`PointsRepository.add` 在 Task 4 定义与使用；`DomainEventCodec.decode(String, String)` 与 `DomainEventStore` 调用一致。
