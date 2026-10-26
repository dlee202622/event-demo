# 中间件接入 Demo 设计文档

## 概述

在现有 DDD 事件驱动 demo 项目中，新增两个独立的 Maven 子模块，分别展示 RabbitMQ 和 RocketMQ 的核心特性。现有代码和内存事件流保持不变。

## 项目结构

根 pom.xml 改为 `packaging=pom` 的多模块 parent，现有 demo 模块的代码不动，新增两个子模块：

```
demo/
├── pom.xml                              (parent, packaging=pom)
├── docker-compose.yml                   (RabbitMQ + RocketMQ 服务)
├── demo-core/
│   ├── pom.xml
│   └── src/...                          (现有代码迁入，不变)
├── demo-rabbitmq/
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/demo/rabbitmq/
│       │   ├── RabbitMQDemoApplication.java
│       │   ├── config/RabbitMQConfig.java
│       │   ├── producer/
│       │   │   ├── SimpleProducer.java
│       │   │   ├── WorkProducer.java
│       │   │   ├── FanoutProducer.java
│       │   │   ├── DirectProducer.java
│       │   │   ├── TopicProducer.java
│       │   │   └── ConfirmProducer.java
│       │   ├── consumer/
│       │   │   ├── SimpleConsumer.java
│       │   │   ├── WorkConsumer.java
│       │   │   ├── FanoutConsumer.java
│       │   │   ├── DirectConsumer.java
│       │   │   ├── TopicConsumer.java
│       │   │   └── DeadLetterConsumer.java
│       │   └── controller/
│       │       └── RabbitMQDemoController.java
│       └── resources/
│           └── application.yaml
└── demo-rocketmq/
    ├── pom.xml
    └── src/main/
        ├── java/com/example/demo/rocketmq/
        │   ├── RocketMQDemoApplication.java
        │   ├── config/RocketMQConfig.java
        │   ├── producer/
        │   │   ├── SimpleProducer.java
        │   │   ├── OrderlyProducer.java
        │   │   ├── DelayProducer.java
        │   │   ├── TransactionProducer.java
        │   │   └── BroadcastProducer.java
        │   ├── consumer/
        │   │   ├── SimpleConsumer.java
        │   │   ├── OrderlyConsumer.java
        │   │   ├── TagFilterConsumer.java
        │   │   ├── DelayConsumer.java
        │   │   ├── TransactionConsumer.java
        │   │   └── BroadcastConsumer.java
        │   ├── listener/
        │   │   └── TransactionListenerImpl.java
        │   └── controller/
        │       └── RocketMQDemoController.java
        └── resources/
            └── application.yaml
```

## 根 pom.xml 变更

现有 pom.xml 添加以下内容，不删除任何已有依赖：

- `<packaging>pom</packaging>`
- `<modules>` 声明三个子模块（当前项目自身 + 两个新模块）
- 现有 demo 自身的 artifactId 改为子模块引用，或保留为默认模块

具体做法：将现有项目代码移入 `demo-core` 子模块，根 pom 变为纯 parent/aggregator（`packaging=pom`，无 `src/`）。这样每个模块职责清晰，互不干扰。

```
demo/
├── pom.xml                  (parent, packaging=pom, 无 src/)
├── demo-core/               (现有代码迁入)
│   ├── pom.xml
│   └── src/...
├── demo-rabbitmq/
│   ├── pom.xml
│   └── src/...
├── demo-rocketmq/
│   ├── pom.xml
│   └── src/...
└── docker-compose.yml
```

根 pom.xml 变更：

- `<packaging>pom</packaging>`
- `<modules>` 声明三个子模块
- 保留 `spring-boot-starter-parent` 继承
- 在 `<dependencyManagement>` 中统一管理 Spring Boot 版本，子模块不再重复声明 parent

## RabbitMQ Demo 设计

### 依赖

- `spring-boot-starter-amqp`：Spring AMQP 自动配置
- `spring-boot-starter-webmvc`：REST 接口

### 展示特性

#### 1. 简单队列 (Simple Queue)

- 生产者发送消息到 `demo.simple.queue`
- 消费者监听并处理消息
- 展示最基本的点对点通信

#### 2. 工作队列 (Work Queue)

- 同一个队列 `demo.work.queue` 绑定多个消费者
- 消息轮询分发，展示负载均衡
- 手动 ACK 确认机制

#### 3. 发布/订阅 (Fanout Exchange)

- 创建 `demo.fanout.exchange` + 多个队列绑定
- 生产者发消息到 fanout exchange
- 所有绑定队列都收到消息副本，展示广播

#### 4. 路由模式 (Direct Exchange)

- 创建 `demo.direct.exchange`
- 队列按 routing key 绑定（如 `order.created`, `order.paid`）
- 生产者指定 routing key 发送，只有匹配的队列收到

#### 5. 主题模式 (Topic Exchange)

- 创建 `demo.topic.exchange`
- 通配符路由：`*` 匹配一个词，`#` 匹配零或多个词
- 示例 binding key：`order.*`、`#.created`

#### 6. 消息确认 (Publisher Confirm)

- 开启 `publisher-confirm-type: correlated`
- 生产者注册 ConfirmCallback，确认消息到达 broker
- 展示 `publisher-returns` 处理不可路由消息

#### 7. 死信队列 (Dead Letter Queue)

- 主队列配置 `x-dead-letter-exchange` 和 `x-message-ttl`
- 消息过期或被 reject 后自动转入死信队列 `demo.dlx.queue`
- 死信消费者处理异常消息

### REST 接口

`RabbitMQDemoController` 提供以下端点：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/rabbitmq/simple` | 发送简单消息 |
| POST | `/api/rabbitmq/work` | 发送工作队列消息 |
| POST | `/api/rabbitmq/fanout` | 广播消息 |
| POST | `/api/rabbitmq/direct` | 路由消息（参数含 routingKey） |
| POST | `/api/rabbitmq/topic` | 主题消息（参数含 routingKey） |
| POST | `/api/rabbitmq/confirm` | 带确认的消息 |
| POST | `/api/rabbitmq/dlx` | 触发死信（发送短 TTL 消息） |

### 配置

```yaml
spring:
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

## RocketMQ Demo 设计

### 依赖

- `rocketmq-spring-boot-starter`（Apache RocketMQ Spring 集成）
- `spring-boot-starter-webmvc`：REST 接口

### 展示特性

#### 1. 基础收发 (Simple)

- 生产者通过 `RocketMQTemplate` 发送消息到 `demo-simple-topic`
- 消费者使用 `@RocketMQMessageListener` 监听
- 最基本的发送与消费

#### 2. 顺序消息 (Orderly)

- 生产者使用 `syncSendOrderly()` 方法，以 orderId 为 hash key
- 同一 orderId 的消息发送到同一队列，保证消费顺序
- 消费者配置 `consumeMode = ConsumeMode.ORDERLY`

#### 3. 标签过滤 (Tag Filter)

- 生产者发送消息时设置 Tag（如 `TagA`、`TagB`）
- 消费者通过 `selectorExpression = "TagA"` 只接收匹配的消息
- 展示 RocketMQ 原生的消息过滤能力

#### 4. 延迟消息 (Delay)

- 生产者发送消息时设置 `delayLevel`（如 level 3 = 10s）
- 消费者在延迟时间到达后才收到消息
- 展示定时投递场景

#### 5. 事务消息 (Transaction)

- 生产者使用 `sendMessageInTransaction()` 发送半消息
- 实现 `RocketMQLocalTransactionListener`：
  - `executeLocalTransaction()`：执行本地事务
  - `checkLocalTransaction()`：回查本地事务状态
- 展示分布式事务的最终一致性

#### 6. 广播消费 (Broadcast)

- 消费者配置 `messageModel = MessageModel.BROADCASTING`
- 同一消费者组的所有实例都收到每条消息
- 展示通知类场景

### REST 接口

`RocketMQDemoController` 提供以下端点：

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/rocketmq/simple` | 发送普通消息 |
| POST | `/api/rocketmq/orderly` | 发送顺序消息（参数含 orderKey） |
| POST | `/api/rocketmq/tag` | 发送带标签消息（参数含 tag） |
| POST | `/api/rocketmq/delay` | 发送延迟消息（参数含 delayLevel） |
| POST | `/api/rocketmq/transaction` | 发送事务消息 |
| POST | `/api/rocketmq/broadcast` | 发送广播消息 |

### 配置

```yaml
rocketmq:
  name-server: localhost:9876
  producer:
    group: demo-producer-group
    send-message-timeout: 3000
server:
  port: 8082
```

## Docker Compose

项目根目录提供 `docker-compose.yml`，一键启动所需中间件：

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
    ports:
      - "10911:10911"
      - "8091:8081"
```

## 端口分配

| 服务 | 端口 |
|------|------|
| demo-core (ddd-event-driven-demo) | 8080 |
| RabbitMQ demo | 8081 |
| RocketMQ demo | 8082 |
| RabbitMQ 管理界面 | 15672 |
| RocketMQ NameServer | 9876 |
| RocketMQ Broker Proxy | 8091 |

## 不在范围内

- demo-core 模块仅做目录迁移，不修改任何现有业务代码
- 不引入 Kafka 或其他中间件
- 不实现中间件集群或高可用配置
- 不添加消息持久化到数据库的演示
- 不做性能测试或压测
