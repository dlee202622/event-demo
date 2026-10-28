# demo-rocketmq：RocketMQ 入门 Demo

和 `demo-rabbitmq` 刻意覆盖同一批场景，方便你横向对比两个中间件的差异。本模块用
**RocketMQ 5.3.1（Docker）+ rocketmq-spring-boot-starter 2.3.1** 演示六种最常见的用法：

| # | 场景 | 接口 | 想让你理解什么 |
| - | --- | --- | --- |
| 1 | 普通消息 | `POST /api/rocketmq/simple` | Producer → Topic → Consumer 的基础收发 |
| 2 | 顺序消息 | `POST /api/rocketmq/orderly` | 同一业务 key 的消息**严格有序** |
| 3 | 标签过滤 | `POST /api/rocketmq/tag` | 一个 Topic 内按 **Tag** 二次分类 |
| 4 | 延迟消息 | `POST /api/rocketmq/delay` | 到点才投递，不用自己扫表 |
| 5 | 事务消息 | `POST /api/rocketmq/transaction` | 半消息 + 本地事务 + 回查 |
| 6 | 广播消费 | `POST /api/rocketmq/broadcast` | 每个实例都收到全量消息 |

> 端口：Demo 应用 `8082`，NameServer `9876`，Broker `10911/10909`，Dashboard `19876`。

---

## 1. 五分钟跑起来

前置：JDK 21、Docker；在**仓库根目录**执行。

```bash
# 1) 启动 NameServer + Broker + Dashboard
docker compose up -d rocketmq-namesrv rocketmq-broker rocketmq-dashboard

# 2) 启动 Demo
./mvnw -pl demo-rocketmq spring-boot:run
```

打开 <http://localhost:8081/>（测试页由 `demo-rabbitmq` 提供），切到 **RocketMQ (8082)**
标签页即可点按钮；它通过 CORS 直接调用 8082 的接口，所以两个应用都要启动。
`demo-rocketmq` 自己**没有静态页面**，访问 <http://localhost:8082/> 会返回 404。
Dashboard 在 <http://localhost:19876/>（无需登录）。

启动成功的关键日志：

```text
a producer (demo-producer-group) init on namesrv localhost:9876
running container: ... topic='demo-simple-topic', ... messageModel=CLUSTERING
Started RocketMQDemoApplication in 5.8 seconds
```

> **为什么本地应用能连上容器里的 Broker？** 仓库根目录的 `broker.conf` 写了
> `brokerIP1=host.docker.internal`，Broker 注册到 NameServer 的是这个宿主机可达地址。
> 如果删掉这行，应用会拿到容器内网 IP（如 `172.x.x.x`）而连接超时——这是新手最常踩的坑。

---

## 2. 先建立四个概念

```text
Producer ──▶ NameServer（路由注册/发现，无状态，可集群）
                │  返回：topic 在哪个 Broker、有哪些队列
                ▼
             Broker（存消息，主从部署）
                │
          Topic = 逻辑分类
                │
        ┌───────┴───────┐
      Queue 0         Queue 1 ...   ← 真正存消息的分区（默认每 Topic 4 个读写队列）
                │
             Consumer（同一个 ConsumerGroup 内的实例分摊队列）
```

- **NameServer**：轻量注册中心，Broker 定时上报 Topic 路由，生产者和消费者来查。节点之间不通信、无强一致。
- **Broker**：消息存储与投递的核心，负责持久化、长轮询、消费位点（offset）。
- **Topic / Queue**：Topic 是分类，Queue 是分区。同一 Topic 的消息分散在多个 Queue 上，
  所以 **RocketMQ 默认只保证「队列内有序」，不保证全局有序**。
- **Tag**：Topic 下的二级标签，Broker 侧按 Tag 过滤，能减少无效网络传输。
- **ProducerGroup / ConsumerGroup**：一组逻辑相同的生产/消费者。集群消费时组内实例分摊队列；
  **不同 Group 各自维护自己的 offset**，互不影响。

和 RabbitMQ 最大的心智差异：

| RabbitMQ | RocketMQ |
| --- | --- |
| Exchange + Binding 决定消息进哪个队列 | 直接指定 Topic（+Tag），由 Broker 分配队列 |
| 队列是消费单位，多消费者抢同一条队列 | 队列是分区，同组消费者**各分到若干队列** |
| 消息被消费即删除 | 消息按时间/大小保留，靠 offset 表示消费进度，可回溯 |

---

## 3. 代码地图

```
demo-rocketmq/src/main/
├── resources/application.yaml              # name-server + producer group + 超时
└── java/com/example/demo/rocketmq/
    ├── controller/RocketMQDemoController.java   # 6 个基础场景接口
    ├── controller/ReliabilityController.java    # 幂等 / 顺序 / 积压 / 持久化接口
    ├── producer/*Producer.java                  # 每个场景一个生产者
    ├── consumer/*Consumer.java                  # 每个场景一个消费者（注解声明）
    ├── listener/TransactionListenerImpl.java    # 事务消息的本地事务 + 回查
    └── support/                                 # 内存幂等表、消费者暂停控制
```

配置只有三行关键内容：

```yaml
rocketmq:
  name-server: localhost:9876
  producer:
    group: demo-producer-group
    send-message-timeout: 3000
```

消费者的 Topic、Group、消费模式全部写在 `@RocketMQMessageListener` 注解上，
阅读顺序建议：**Controller → 对应 Producer → 对应 Consumer**。

---

## 4. 十个场景逐个跑

所有接口返回 `"status":"sent"` 仅表示**发送成功（Broker 已收下）**，消费是异步的。
看效果请关注应用日志；Topic 是否创建、有多少消息、消费有没有堆积，看 Dashboard。

> **首次发送新 Topic 的等待**：本 Demo 的 Topic 靠 Broker 自动创建，消费者的路由信息默认
> 每 30 秒刷新一次。第一次调用某个场景后，应用日志可能要等十几秒到半分钟才出现，属正常现象；
> 之后再发就是秒级。看到「发送成功但暂时没消费日志」不要急着怀疑代码。

### 4.1 普通消息：最基础的一条线

```bash
curl -s -X POST 'http://localhost:8082/api/rocketmq/simple?message=hello-rocketmq'
```

预期响应：

```json
{"topic":"demo-simple-topic","status":"sent","message":"hello-rocketmq"}
```

预期日志：

```text
[SimpleConsumer] received: hello-rocketmq
```

代码就两行，一边 `convertAndSend("demo-simple-topic", message)`，一边：

```java
@RocketMQMessageListener(topic = "demo-simple-topic", consumerGroup = "demo-simple-consumer-group")
public class SimpleConsumer implements RocketMQListener<String> { ... }
```

要点：

- Topic 默认由生产者**自动创建**（Broker 开启 `autoCreateTopicEnable`）；生产环境通常预先建好 Topic 并规划队列数。
- 默认是**集群消费（CLUSTERING）**：同组多个实例分摊消息，一条消息在组内只被消费一次。
- 消费者返回正常即视为消费成功并提交 offset；抛异常会按重试策略重投。

Dashboard 观察：**主题** 页能看到 `demo-simple-topic` 及队列数；**消费者** 页能看到
`demo-simple-consumer-group` 的消费位点与堆积量（Diff）。

---

### 4.2 顺序消息：同一个 key 严格有序

```bash
for s in step1 step2 step3; do
  curl -s -X POST "http://localhost:8082/api/rocketmq/orderly?orderKey=ORDER-1001&message=$s"; echo
done
```

预期日志（顺序保持 step1 → step2 → step3）：

```text
[OrderlyConsumer] thread=ConsumeMessageThread_demo-orderly-consumer-group_1 received: step1
[OrderlyConsumer] thread=ConsumeMessageThread_demo-orderly-consumer-group_2 received: step2
[OrderlyConsumer] thread=ConsumeMessageThread_demo-orderly-consumer-group_3 received: step3
```

实现分两步：

```java
// 生产端：按订单号选择队列，保证同一订单进入同一队列
rocketTemplate.syncSendOrderly("demo-orderly-topic", message, orderKey);

// 消费端：对该队列加锁，逐条消费
@RocketMQMessageListener(topic = "demo-orderly-topic", consumerGroup = "...",
        consumeMode = ConsumeMode.ORDERLY)
```

要点（很重要）：

- **有序的范围是「同一个队列」**。`orderKey` 相同 → 同一个队列 → 有序；
  `orderKey` 不同 → 可能落在不同队列 → **不保证先后**。
- 线程号不同不代表乱序：ORDERLY 模式保证的是「同一队列当前消息处理完才取下一条」，
  线程池可能换线程，但顺序不变。
- 全局顺序需要把 Topic 队列数设为 1，吞吐会急剧下降，一般只在特殊场景使用。
- 生产端重试、消费端失败重投都可能破坏顺序，需要配合幂等和「失败即阻塞该队列」的策略。

---

### 4.3 标签过滤：一个 Topic 内再分类

```bash
curl -s -X POST 'http://localhost:8082/api/rocketmq/tag?tag=TagA&message=tag-a-msg'
curl -s -X POST 'http://localhost:8082/api/rocketmq/tag?tag=TagB&message=tag-b-msg'
```

预期日志（**只有 TagA 那条**）：

```text
[TagFilterConsumer] received (TagA only): tag-a-msg
```

原因：消费者声明了 `selectorExpression = "TagA"`，Broker 在投递前就过滤掉了 TagB。
生产者发送时把 Tag 拼在 Topic 后面：

```java
rocketTemplate.send("demo-tag-topic:" + tag, MessageBuilder.withPayload(message).build());
//                               ^^^^^^ Topic : Tag
```

要点：

- Tag 适合「同一类消息的细分」，比如 `order-topic` 下分 `created` / `paid` / `cancelled`；
- 一个消费者可以订阅多个 Tag：`selectorExpression = "TagA || TagB"`；
- 过滤发生在 Broker 侧，**TagB 消息仍然会存进 Topic**，只是不会推给这个消费者。
  换一个 `selectorExpression = "*"` 的消费组就能消费到，消息并没有丢。

---

### 4.4 延迟消息：到点才投递

```bash
curl -s -X POST 'http://localhost:8082/api/rocketmq/delay?message=delay-msg&delayLevel=3'
```

预期日志（发送后约 **10 秒** 才出现）：

```text
[DelayConsumer] received at 1793165488723: delay-msg
```

`delayLevel=3` 表示延迟等级 3。RocketMQ 固定等级如下：

| level | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 延迟 | 1s | 5s | 10s | 30s | 1m | 2m | 3m | 4m | 5m |

继续往后是 6m ~ 10m、20m、30m、1h、2h。Demo 的接口参数 `delayLevel` 默认就是 `3`。

要点：

- 经典延迟消息只支持这些**固定等级**，不能任意指定秒数（5.x 新增了定时消息 API，可按时间戳投递）；
- 适合「30 分钟未支付自动取消」这类场景；但生产上超时取消更常见的是**定时任务扫表**
  （本仓库订单服务用的 XXL-JOB 就是这种做法），延迟消息适合量大且无需全局查询的场景；
- 到期前消息暂存在 Broker 内部的调度 Topic（`SCHEDULE_TOPIC_XXXX`），在业务 Topic
  `demo-delay-topic` 上看不到，Dashboard 也不会显示成业务堆积，这是正常的。

---

### 4.5 事务消息：本地事务和发消息的最终一致

```bash
curl -s -X POST 'http://localhost:8082/api/rocketmq/transaction?message=tx-msg'
```

预期日志：

```text
[TransactionListener] executing local transaction for: [116, 120, 45, 109, 115, 103]
[TransactionListener] local transaction committed
[TransactionConsumer] received transactional message: tx-msg
```

> 第一行方括号里是 `tx-msg` 的字节数组。事务回调拿到的是**原始 Message**，
> 还没有经过消息转换器，所以直接打印 `getPayload()` 会看到 byte[]，属正常现象；
> 业务代码里通常反序列化后再使用。

流程（两阶段提交思想）：

```text
1. Producer ──发送半消息（Half Message）──▶ Broker      ← 此时消费者不可见
2. Broker ──写入成功──▶ Producer
3. Producer ──执行本地事务（写库）──▶ 返回 COMMIT / ROLLBACK / UNKNOWN
4. COMMIT   → Broker 把半消息转成正式消息，消费者可见
   ROLLBACK → Broker 删除半消息
   UNKNOWN  → Broker 定时回查 checkLocalTransaction()，由它给最终结论
```

代码：

```java
// 发送方
rocketTemplate.sendMessageInTransaction("demo-tx-topic", message, null);

// 监听器
@Override
public RocketMQLocalTransactionState executeLocalTransaction(Message msg, Object arg) {
    // 在这里写本地库；成功返回 COMMIT，失败返回 ROLLBACK
}
@Override
public RocketMQLocalTransactionState checkLocalTransaction(Message msg) {
    // Broker 回查：查本地库判断事务到底成没成
}
```

要点：

- 事务消息解决的是「**本地事务 + 发消息**」的原子性，不是分布式事务框架；
- `checkLocalTransaction` 才是可靠性的关键——进程崩溃、返回 UNKNOWN 时靠它兜底，
  所以本地事务结果必须**可查询**（落库，而不是只存在内存里）；
- 消费端仍需幂等：消息可能重复投递（至少一次语义）。

---

### 4.6 广播消费：每个实例都收到全量

```bash
for i in 1 2 3; do
  curl -s -X POST "http://localhost:8082/api/rocketmq/broadcast?message=broadcast-$i"; echo
done
```

预期日志（三条都收到）：

```text
[BroadcastConsumer] received: broadcast-1
[BroadcastConsumer] received: broadcast-2
[BroadcastConsumer] received: broadcast-3
```

差别只在一行注解：

```java
@RocketMQMessageListener(
        topic = "demo-broadcast-topic",
        consumerGroup = "demo-broadcast-consumer-group",
        messageModel = MessageModel.BROADCASTING)   // 默认是 CLUSTERING
```

两种消费模式对比：

| | CLUSTERING（集群，默认） | BROADCASTING（广播） |
| --- | --- | --- |
| 组内实例 | 分摊队列，一条消息只被一个实例消费 | 每个实例都收到全量消息 |
| offset | 存在 Broker | 存在消费者本地 |
| 典型用途 | 业务处理、水平扩容 | 本地缓存刷新、配置推送、广播通知 |
| 扩容影响 | 增加实例→队列重新分配 | 新实例只收**启动后**的新消息 |

> **广播模式不适合「补历史」**：offset 存在本地，且默认从最新位点开始，
> 重启/新实例不会把之前没消费的消息再投一遍。想看效果，等应用起来后再发消息。

---

### 4.7 幂等：重复投递只处理一次

RocketMQ 同样只保证「至少一次」：超时重发、消费失败重试、Broker 重投都会让同一条消息到达多次。
幂等要靠**消息体里的业务唯一 ID**，而不是 Broker 的 msgId（重投后会变）。

```bash
curl -s -X POST 'http://localhost:8082/api/rocketmq/idempotent?messageId=RID-1&duplicate=true'
```

响应：

```json
{"messageId":"RID-1","topic":"demo-idempotent-topic","sent":"2"}
```

预期日志（两条几乎同时处理）：

```text
[IdempotentConsumer] processed: RID-1
[IdempotentConsumer] duplicate ignored: RID-1
```

注意：幂等消费者是**并发消费**（多个 `ConsumeMessageThread`），两条重复消息可能同时到达，
所以这两行日志的**先后顺序可能互换**，只要 `processed` 只出现一次就说明去重生效。
实现与 RabbitMQ 版本一致：`InMemoryIdempotentStore.tryAcquire` 用 `ConcurrentHashMap.compute`
原子抢占，TTL 10 分钟，业务失败时 `release` 归还重试机会。

生产做法同样是 Redis `SET NX EX` 或数据库唯一索引；进程内 Map 只适合单实例 Demo，
重启和多实例都会失效。详见 `demo-rabbitmq/README.md` 的 4.8 节。

---

### 4.8 顺序：同一 sharding key 进同一队列

```bash
curl -s -X POST 'http://localhost:8082/api/rocketmq/ordered-batch?orderKey=ORDER-1&count=10'
```

预期日志（严格 step-1 → step-10）：

```text
[OrderlyConsumer] thread=ConsumeMessageThread_demo-orderly-consumer-group_1 received: step-1
[OrderlyConsumer] thread=..._2 received: step-2
...
[OrderlyConsumer] thread=..._9 received: step-10
```

**不要用线程名判断顺序**：ORDERLY 模式会在同一个队列上加锁逐条消费，但每次提交到线程池的
线程可能不同。判断依据是消息序号，不是 `thread=`。

生产端的关键是 `syncSendOrderly`：

```java
rocketTemplate.syncSendOrderly("demo-orderly-topic", message, orderKey);
```

它用 `orderKey` 哈希选队列：**同一个 key 的消息一定进同一个队列**，不同 key 分散到不同队列。
消费端加 `consumeMode = ConsumeMode.ORDERLY` 后，Broker 对每个队列逐条投递并等待消费成功，
因此同 key 消息严格有序，不同队列之间仍可并行。

反例：普通发送 + 并发消费（`CONCURRENTLY`）：

```bash
curl -s -X POST 'http://localhost:8082/api/rocketmq/unordered?count=10'
```

日志会出现 step-3、step-1、step-9、step-8 ... 的乱序，因为多个线程同时处理同一 Topic。

要点：

- RocketMQ 的顺序是**队列级**的，不是 Topic 全局有序；需要全局有序只能单队列，吞吐会明显下降；
- `orderKey` 必须是稳定的业务键（订单号、用户 ID），不能每次随机；
- 增加队列数会让 key 重新哈希映射，扩缩容期间顺序可能短暂受影响，低峰期操作；
- ORDERLY 消费失败默认会阻塞当前队列重试，务必做好失败告警，避免一个毒消息拖住整个队列。

---

### 4.9 积压：暂停消费者，观察 Diff 增长

```bash
# 1) 暂停消费者
curl -s -X POST 'http://localhost:8082/api/rocketmq/backlog/pause'

# 2) 发 200 条
curl -s -X POST 'http://localhost:8082/api/rocketmq/backlog/send?count=200'

# 3) 打开 Dashboard 看堆积：消费者 → demo-backlog-consumer-group → Diff
#    http://localhost:19876/

# 4) 恢复消费
curl -s -X POST 'http://localhost:8082/api/rocketmq/backlog/resume'
```

`/backlog/status` 只返回暂停状态，堆积量要看 Dashboard：

```json
{"topic":"demo-backlog-topic","consumerGroup":"demo-backlog-consumer-group","suspended":"true", ...}
```

暂停是通过消费者客户端的 `DefaultMQPushConsumer.suspend()/resume()` 实现的
（`support/RocketMQConsumerControl`）：

```java
DefaultRocketMQListenerContainer container = ... // 按 consumerGroup 找到容器
container.getConsumer().suspend();               // 停止拉取
container.getConsumer().resume();                // 继续拉取
```

一个实测细节：**暂停只阻止继续拉取，已经拉到本地 processQueue 的消息仍会被消费**。
所以点完暂停再发消息，Diff 可能先下降几条再稳定，这是正常的，不是暂停失效。

生产上处理积压的思路：

- **先看 Diff 增速**：持续增长说明消费能力不足；忽高忽低多为下游抖动；
- **提升并行度**：消费并行度上限由 Topic 的队列数决定，队列不够时加机器也没用；
- **批量消费**：`consumeMessageBatchMaxSize` 配合批量写库，比逐条快很多；
- **保护下游**：限流、降级、异步落库，避免把数据库打死造成雪崩；
- **毒消息**：重试 16 次后进入 `%DLQ%<consumerGroup>`，必须监控并人工/自动处理。

---

### 4.10 持久化：重启 Broker 后消息还在吗

RocketMQ 的消息统一写入 CommitLog，`syncSend` 返回 `SendStatus.SEND_OK` 表示 Broker 已接收并落盘
（默认 `ASYNC_FLUSH`，即写入页缓存，由操作系统异步刷盘）。

```bash
# 1) 暂停消费者
curl -s -X POST 'http://localhost:8082/api/rocketmq/persistence/pause'

# 2) 发 3 条
curl -s -X POST 'http://localhost:8082/api/rocketmq/persistence/send?count=3'

# 3) 重启 Broker（不是 down/up）
docker compose restart rocketmq-broker

# 4) 恢复消费
curl -s -X POST 'http://localhost:8082/api/rocketmq/persistence/resume'
```

预期日志（重启后仍能消费到重启前的 3 条）：

```text
[PersistenceConsumer] received: persist-1
[PersistenceConsumer] received: persist-2
[PersistenceConsumer] received: persist-3
```

也可以用命令行确认消息确实写进了 Topic：

```bash
docker exec demo-rocketmq-broker-1 sh -c \
  'cd /home/rocketmq/rocketmq-4.9.4/bin && ./mqadmin topicStatus \
   -n rocketmq-namesrv:9876 -t demo-persistence-topic'
```

要点：

- `syncSend` 只保证「Broker 已收下」，不等于「消费者已处理」；消费失败仍会重试，幂等不能省；
- 默认 `ASYNC_FLUSH` 下，**Broker 进程重启不丢**（数据在页缓存/磁盘），但**整机断电可能丢最后一批**。
  强可靠场景要开 `flushDiskType=SYNC_FLUSH`，并配主从同步或 DLedger（Raft）；
- 本仓库的 Broker **没有挂数据卷**，`docker compose down` 会删除容器和 CommitLog，验证请用 `restart`；
- RocketMQ 默认保留 72 小时（`fileReservedTime`），过期清理；需要回溯就靠 Consumer 位点重放，
  这是它和 RabbitMQ「消费即删除」最大的差异之一。

---

## 5. Dashboard 怎么用（19876）

- **驾驶舱 / 集群**：Broker 是否在线、消息量与 TPS。
- **主题**：确认 Topic 是否自动创建、队列数（读写队列）、消息写入量。
- **消费者**：按 ConsumerGroup 看订阅关系、消费位点、堆积（Diff）。排障先看这里。
- **消息**：按 Topic + 时间范围/MessageID 查询消息内容与投递轨迹（5.x Dashboard 支持）。

排查「消息为什么没被消费」的固定顺序：
**Topic 有没有消息 → ConsumerGroup 有没有在线实例 → 订阅的 Tag 匹配不匹配 → 堆积是否在增长 → 应用日志有没有异常。**

---

## 6. 常见问题

| 现象 | 原因 | 解决 |
| --- | --- | --- |
| 启动日志正常，发送报 `sendDefaultImpl call timeout` / 连 `172.x.x.x:10911` 超时 | Broker 注册的是容器内网 IP | 确认 `broker.conf` 有 `brokerIP1=host.docker.internal`，重启 Broker |
| Dashboard 打不开或列表为空 | Dashboard 比 NameServer 先起、或 NameServer 未就绪 | `docker compose restart rocketmq-dashboard`；确认 9876 已监听 |
| 报 `No route info of this topic` | Broker 关闭了自动建 Topic，或 Topic 尚未上报 | 在 Dashboard 手动建 Topic，或检查 Broker `autoCreateTopicEnable` |
| 延迟消息迟迟不到 | 延迟等级理解错，level 3 是 10 秒不是 3 秒 | 对照第 4.4 节等级表；等待对应时长 |
| 顺序消息偶尔乱序 | 用了 `convertAndSend` 或不同 `orderKey` | 必须 `syncSendOrderly(topic, msg, shardingKey)`，且同一业务用同一 key |
| 广播模式只收到部分消息 | 广播 offset 在本地，且从最新位点开始 | 应用启动后再发消息；不要指望广播补历史 |
| TagB 消息「消失」 | 消费者只订阅了 TagA | Broker 侧过滤，消息仍在 Topic 中；换订阅表达式即可消费 |
| 端口冲突 | 8082 已被占用 | `lsof -nP -iTCP:8082 -sTCP:LISTEN` 结束旧进程 |

---

## 7. 从 Demo 到生产还差什么

- **Topic 规划**：预先创建 Topic、按峰值 QPS 规划队列数（队列数决定最大并行度，后期只能增不能减）；
- **消费幂等**：至少一次投递 + 重试意味着可能重复，业务侧用唯一键/状态机去重；
- **重试与死信**：消费失败默认重试 16 次后进入 `%DLQ%<consumerGroup>`，需要监控和人工处理；
- **顺序与并发的取舍**：ORDERLY 会牺牲吞吐；能靠业务状态机容忍乱序时优先并发消费；
- **事务消息的回查实现**：本地事务表必须可查、可幂等，回查逻辑要能处理「事务进行中」；
- **持久化**：默认 `ASYNC_FLUSH`，进程重启不丢但断电可能丢最后一批；强可靠要开 `SYNC_FLUSH` 并配主从 / DLedger，Broker 数据目录必须挂卷；
- **监控**：消费堆积（Diff）、发送失败率、RT、DLQ 数量，比看应用日志更早发现问题。

---

## 8. 和 RabbitMQ Demo 的关系

| 维度 | RocketMQ（本模块） | RabbitMQ（`demo-rabbitmq`） |
| --- | --- | --- |
| 路由模型 | Topic + Tag，模型简单、天然分区 | Exchange + Binding，路由灵活 |
| 消息保留 | 按时间/大小保留，可按 offset 回溯 | 消费后删除，回溯需额外设计 |
| 顺序 | 同一队列 / 同一 sharding key 有序 | 单队列单消费者有序 |
| 延迟消息 | 原生延迟等级 / 定时消息 | TTL + DLX 间接实现 |
| 事务消息 | 半消息 + 本地事务回查 | 无原生支持，本地消息表 / Outbox |
| 广播 | `BROADCASTING`，实例各自维护 offset | Fanout 交换机复制到多个队列 |
| 典型场景 | 高吞吐、顺序、事务、大规模堆积 | 灵活路由、低延迟、轻量运维 |

两个 Demo 的接口、日志格式刻意保持一致，建议对照着各跑一遍，再回到本仓库的
`demo-order-service` 看真实业务里 Outbox + 事件消费是怎么落地的。
