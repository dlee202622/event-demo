# demo-rabbitmq：RabbitMQ 入门 Demo

这是一个「打开页面点按钮就能看懂 RabbitMQ」的最小工程，覆盖日常开发最常用的
5 种收发模式，外加 2 个可靠性特性（Publisher Confirm + Return、死信队列 / DLX）。

| # | 场景 | 接口 | 想让你理解什么 |
| - | --- | --- | --- |
| 1 | 简单队列 | `POST /api/rabbitmq/simple` | 生产者 → 队列 → 消费者，最基础的一条线 |
| 2 | 工作队列 | `POST /api/rabbitmq/work` | 多个消费者**竞争**消费同一条队列（负载均衡） |
| 3 | Fanout 广播 | `POST /api/rabbitmq/fanout` | 一条消息**复制**给所有绑定队列 |
| 4 | Direct 路由 | `POST /api/rabbitmq/direct` | 按 `routingKey` **精确**投递 |
| 5 | Topic 路由 | `POST /api/rabbitmq/topic` | 按 `routingKey` **通配符**投递 |
| 6 | 发布确认 | `POST /api/rabbitmq/confirm` | Broker 确认 + 路由失败回调（可靠性） |
| 7 | 死信队列 | `POST /api/rabbitmq/dlx` | 消费失败的消息去哪了 |

> 端口：Demo 应用 `8081`，RabbitMQ 管理台 `15672`（guest / guest）。

---

## 1. 五分钟跑起来

前置：JDK 21、Docker；在**仓库根目录**执行。

```bash
# 1) 启动 RabbitMQ（带管理台）
docker compose up -d rabbitmq

# 2) 启动 Demo（首次会自动编译）
./mvnw -pl demo-rabbitmq spring-boot:run
```

然后打开 <http://localhost:8081/>，页面上的 **RabbitMQ (8081)** 标签页就是全部场景的按钮式入口；
再开一个窗口打开管理台 <http://localhost:15672/>（账号密码都是 `guest`），边点按钮边看队列变化。

看到这行日志说明连接成功、消费者已就绪：

```text
Started RabbitMQDemoApplication in 2.1 seconds
```

不想用页面的话，下面每个场景都给了 `curl` 命令。

---

## 2. 先建立三个概念

```text
Producer ──publish──▶ Exchange ──binding──▶ Queue ──consume──▶ Consumer
  生产者                 交换机      绑定          队列           消费者
```

- **Producer（生产者）**：发消息的应用，不关心消息最终进哪个队列。
- **Exchange（交换机）**：只负责「按规则转发」，自己**不存消息**。
- **Binding（绑定）**：交换机和队列之间的规则；Direct/Topic 由 `routingKey` 匹配，Fanout 忽略 key。
- **Queue（队列）**：真正存消息的地方，消息被消费成功后删除。
- **Consumer（消费者）**：`@RabbitListener(queues = "...")` 标注的方法，容器负责拉取和回调。

四种交换机（本 Demo 全都有）：

| 类型 | 路由规则 | 本 Demo 的用法 |
| --- | --- | --- |
| default（默认） | `routingKey == 队列名` | `convertAndSend(队列名, 消息)` 实际走的就是它 |
| direct | `routingKey` **完全相等** | `order.created` / `order.paid` |
| fanout | **忽略** `routingKey`，给所有绑定队列 | 广播 |
| topic | `*` 匹配一个单词，`#` 匹配零到多个单词 | `order.*`、`#.created` |

还有一个新手最容易困惑的点：**发送时只写队列名，是怎么到队列的？**
RabbitMQ 内置一个名字为空字符串的默认交换机，每个队列都会自动以「队列名」绑定到它。
所以 `convertAndSend("demo.simple.queue", msg)` 等价于
`convertAndSend("", "demo.simple.queue", msg)`。

---

## 3. 代码地图

```
demo-rabbitmq/src/main/
├── resources/
│   ├── application.yaml            # 连接信息 + publisher-confirm-type: correlated
│   └── static/index.html           # 8081 上的按钮式测试页（同时呼叫 8081/8082）
└── java/com/example/demo/rabbitmq/
    ├── config/RabbitMQConfig.java  # 所有队列 / 交换机 / 绑定，建议先读这个
    ├── config/WebConfig.java       # 测试页跨端口调用用的 CORS
    ├── controller/RabbitMQDemoController.java  # 7 个基础场景接口
    ├── controller/ReliabilityController.java   # 幂等 / 顺序 / 积压 / 持久化接口
    ├── producer/*Producer.java     # 每个场景一个生产者，只有几行
    ├── consumer/*Consumer.java     # 每个场景一个消费者，只打日志
    └── support/                    # 内存幂等表、延迟工具
```

**推荐阅读顺序**：`RabbitMQConfig`（拓扑）→ `RabbitMQDemoController`（入口）→ 对应
`XxxProducer` → 对应 `XxxConsumer`。一个场景的代码量通常不超过 15 行。

交换机与队列的常量名都定义在 `RabbitMQConfig` 里，下文直接用名字，不重复贴代码。

---

## 4. 十一个场景逐个跑

### 4.0 场景速查表

| 场景 | 交换机 | routingKey | 会收到消息的队列 |
| --- | --- | --- | --- |
| simple | `""`（默认） | `demo.simple.queue` | `demo.simple.queue` |
| work | `""`（默认） | `demo.work.queue` | `demo.work.queue`（2 个线程抢） |
| fanout | `demo.fanout.exchange` | 忽略 | `demo.fanout.queue.a` + `.b` |
| direct | `demo.direct.exchange` | `order.created` | `demo.direct.queue.order` |
| direct | `demo.direct.exchange` | `order.paid` | `demo.direct.queue.payment` |
| topic | `demo.topic.exchange` | `order.created` | `demo.topic.queue.all-order` + `.created` |
| confirm | `""`（默认） | `demo.simple.queue` | `demo.simple.queue` |
| dlx | `""`（默认） | `demo.dlq.main.queue` | 先到主队列，被拒绝后进 `demo.dlx.queue` |
| idempotent | `""`（默认） | `demo.idempotent.queue` | `demo.idempotent.queue`（重复只处理一次） |
| ordered | `""`（默认） | `demo.ordered.queue` | `demo.ordered.queue`（1 个消费者） |
| unordered | `""`（默认） | `demo.unordered.queue` | `demo.unordered.queue`（4 个消费者抢） |
| backlog | `""`（默认） | `demo.backlog.queue` | `demo.backlog.queue`（可暂停 / 恢复） |
| persistence | `""`（默认） | `demo.persistence.queue` | `demo.persistence.queue`（重启后对比） |

所有接口都返回 JSON，`status: "sent"` 只代表**消息已交给 Broker**，不代表已被消费。

---

### 4.1 简单队列：一条消息一个消费者

```bash
curl -s -X POST 'http://localhost:8081/api/rabbitmq/simple?message=hello-rabbitmq'
```

预期响应：

```json
{"queue":"demo.simple.queue","message":"hello-rabbitmq","status":"sent"}
```

预期日志：

```text
[SimpleConsumer] received: hello-rabbitmq
```

管理台：**Queues** 列表里能看到 `demo.simple.queue`，因为消费者在线，消息被立刻消费，
`Ready` 基本一直是 0。想看消息堆积的效果，先停掉 Demo 应用，连发几条，再回来看 Ready 数字。

要点：

- 生产者 `SimpleProducer` 只有一行 `rabbitTemplate.convertAndSend(QUEUE, message)`，走默认交换机。
- 默认是容器**自动确认**（acknowledge mode `AUTO`）：监听方法正常返回就 ACK，
  抛异常则按策略重投/丢弃。

---

### 4.2 工作队列：多个消费者抢同一条队列

```bash
for i in 1 2 3 4; do
  curl -s -X POST "http://localhost:8081/api/rabbitmq/work?message=task-$i"; echo
done
```

预期日志（线程名交替，说明两个消费线程在竞争）：

```text
[WorkConsumer] thread=RabbitListenerEndpointContainer#9-1 received: task-1
[WorkConsumer] thread=RabbitListenerEndpointContainer#9-2 received: task-2
[WorkConsumer] thread=RabbitListenerEndpointContainer#9-1 received: task-3
[WorkConsumer] thread=RabbitListenerEndpointContainer#9-2 received: task-4
```

代码里只有一个监听方法，但注解写了 `@RabbitListener(queues = WORK_QUEUE, concurrency = "2")`，
Spring AMQP 会为它开 2 个并发消费者。生产环境通常是**多实例**各自开 1 个消费者，
效果一样：同一条队列上的消息只会被其中一个消费者拿到，天然负载均衡。

要点：

- 一条队列 = 一个「工作池」，**消息不会被复制**。
- 想更公平（快消费者多干活），把 `prefetch` 调小（例如 `spring.rabbitmq.listener.simple.prefetch=1`），
  否则默认一次预取上百条，容易变成两个线程轮流均分。

---

### 4.3 Fanout：一条消息，所有队列都收到

```bash
curl -s -X POST 'http://localhost:8081/api/rabbitmq/fanout?message=broadcast-msg'
```

预期日志（**两条**，队列 A、B 各一条）：

```text
[FanoutConsumer-A] received: broadcast-msg
[FanoutConsumer-B] received: broadcast-msg
```

拓扑：

```text
                         ┌──▶ demo.fanout.queue.a ──▶ FanoutConsumer-A
fanout.exchange ─────────┤
（忽略 routingKey）       └──▶ demo.fanout.queue.b ──▶ FanoutConsumer-B
```

要点：

- 即使发送时 `routingKey` 传了值也会被忽略，所以才用空串。
- 每新增一个下游系统，加一个队列 + 绑定即可，生产者代码不用改。

---

### 4.4 Direct：按 routingKey 精确路由

```bash
curl -s -X POST 'http://localhost:8081/api/rabbitmq/direct?routingKey=order.created&message=order-created'
curl -s -X POST 'http://localhost:8081/api/rabbitmq/direct?routingKey=order.paid&message=order-paid'
```

预期日志：

```text
[DirectConsumer-order] received: order-created
[DirectConsumer-payment] received: order-paid
```

拓扑与「路由失败」：

```text
                                  routingKey = order.created
demo.direct.exchange ─────────────────────────────▶ demo.direct.queue.order
                     └────────────────────────────▶ demo.direct.queue.payment
                                  routingKey = order.paid
```

再发一个没人绑定的 key：

```bash
curl -s -X POST 'http://localhost:8081/api/rabbitmq/direct?routingKey=order.shipped&message=oops'
```

接口仍然返回 `"status":"sent"`，但日志会出现一条 **WARN**：

```text
[ConfirmProducer] message returned: exchange=demo.direct.exchange, routingKey=order.shipped, replyCode=312
```

`312 NO_ROUTE` 表示「交换机收到了，但没有队列要它」，消息被丢弃。这正是
`publisher-returns: true` 的作用：**Broker 确认收到 ≠ 路由成功**，要靠 Return 回调兜底。
生产上应把这类消息记录/告警，否则就是静默丢消息。

---

### 4.5 Topic：通配符路由

绑定规则：

| 队列 | 绑定 key | 含义 |
| --- | --- | --- |
| `demo.topic.queue.all-order` | `order.*` | 以 `order.` 开头、**恰好两段** |
| `demo.topic.queue.created` | `#.created` | **任意前缀**，以 `.created` 结尾 |

```bash
curl -s -X POST 'http://localhost:8081/api/rabbitmq/topic?routingKey=order.created&message=order-created'
curl -s -X POST 'http://localhost:8081/api/rabbitmq/topic?routingKey=order.paid&message=order-paid'
curl -s -X POST 'http://localhost:8081/api/rabbitmq/topic?routingKey=user.created&message=user-created'
```

预期日志：

```text
# order.created：两个队列都匹配
[TopicConsumer-all-order] received: order-created
[TopicConsumer-created] received: order-created

# order.paid：只有 order.* 匹配
[TopicConsumer-all-order] received: order-paid

# user.created：只有 #.created 匹配
[TopicConsumer-created] received: user-created
```

要点：

- `*` = **一个**单词；`#` = **零个或多个**单词，两者都按 `.` 分段。
- 一条消息可以同时进多个队列（上面前两行），每个队列各自有一份完整副本。
- 通配符越多，路由开销越大；事件总线常见的 `event.{type}.{aggregateId}` 就是 Topic 的典型用法。

---

### 4.6 Publisher Confirm + Return：消息真的落地了吗

```bash
curl -s -X POST 'http://localhost:8081/api/rabbitmq/confirm?message=confirm-msg'
```

预期日志：

```text
[SimpleConsumer] received: confirm-msg
[ConfirmProducer] message confirmed by broker, id=confirm-1     # id 是自增序号，每次不同
```

`application.yaml` 里开了：

```yaml
spring:
  rabbitmq:
    publisher-confirm-type: correlated   # 让 confirm 回调带回 CorrelationData
    publisher-returns: true              # 路由失败时回调（未指定队列）
```

`ConfirmProducer.send()` 发送时带了一个 `CorrelationData("confirm-" + 自增序号)`：

```java
CorrelationData correlationData = new CorrelationData("confirm-" + SEQUENCE.incrementAndGet());
rabbitTemplate.convertAndSend("", RabbitMQConfig.SIMPLE_QUEUE, message, correlationData);
```

回调里通过 `correlationData.getId()` 就知道是哪条消息被确认（`ack=true`）或拒绝（`ack=false`）。
**不带 `CorrelationData` 的发送，回调里只能拿到 `id=n/a`**——你在日志里会看到其它场景也打
`confirmed ... id=n/a`，就是这个原因：confirm 回调是全局的，但关联信息需要自己传。

三个容易混淆的概念：

| 机制 | 回答的问题 | 失败时 |
| --- | --- | --- |
| Publisher Confirm | Broker **收下**了吗？ | `ack=false`，可重发 |
| Publisher Return | 收下后**路由到队列**了吗？ | `replyCode=312`，消息被丢弃 |
| Consumer ACK | 消费者**处理完**了吗？ | 重新入队 / 进死信 |

三者都成功，才算一次真正可靠的消息投递。

---

### 4.7 死信队列（DLX）：处理不了的消息去哪

```bash
curl -s -X POST 'http://localhost:8081/api/rabbitmq/dlx?message=dlx-msg'
```

预期日志（先是主队列消费者拒收，然后 DLQ 消费者收到）：

```text
[DeadLetterConsumer-main] received: dlx-msg, will reject to DLQ
WARN ... AmqpRejectAndDontRequeueException: reject to DLQ demo
[DeadLetterConsumer-DLQ] received dead letter: dlx-msg
```

流程：

```text
                    ① 消费者抛 AmqpRejectAndDontRequeueException（不重新入队）
demo.dlq.main.queue ──────────────────────────────────────────┐
   x-dead-letter-exchange = demo.dlx.exchange                 │
   x-dead-letter-routing-key = dlq                            ▼
   x-message-ttl = 3000                              demo.dlx.exchange
                                                              │ binding key: dlq
                                                              ▼
                                                       demo.dlx.queue ──▶ DeadLetterConsumer-DLQ
```

消息变成死信的三种情况：**被拒绝且不重入队**（本 Demo）、**队列/消息 TTL 过期**、**队列满**。
本 Demo 的消费者会主动拒收，所以 TTL 通常来不及生效；想单独观察 TTL 这条路径，可以停掉
Demo 应用、发一条消息，3 秒后管理台里它会从 `demo.dlq.main.queue` 消失并出现在
`demo.dlx.queue`（应用没启动，所以没人消费，正好能看见堆积）。

关键细节：主队列同时配置了 `x-dead-letter-exchange` 和 `x-dead-letter-routing-key=dlq`。
**如果漏配 routing key**，RabbitMQ 会沿用消息原来的 routing key（这里是队列名
`demo.dlq.main.queue`），与 DLX 的绑定键 `dlq` 不匹配，消息会被**静默丢弃**——这个坑很常见。

管理台验证：**Queues** → `demo.dlx.queue`，能看到 `Ready` 里有一条消息（因为 DLQ 消费者
会立刻取走，所以数字很快归零；想看堆积就先停掉应用）。

---

### 4.8 幂等：重复投递只处理一次

Broker 只能保证「至少一次」：消费者处理完还没 ACK 就断线、消息被重投，业务就会收到两遍。
幂等的关键是**消息体里带一个稳定的业务唯一 ID**，消费端用这个 ID 去重。

```bash
curl -s -X POST 'http://localhost:8081/api/rabbitmq/idempotent?messageId=REL-1&duplicate=true'
```

响应（`duplicate=true` 会把同一条业务 ID 连发两次）：

```json
{"messageId":"REL-1","queue":"demo.idempotent.queue","sent":"2"}
```

预期日志：

```text
[IdempotentConsumer] processed: REL-1
[IdempotentConsumer] duplicate ignored: REL-1
```

实现只有两步（`consumer/IdempotentConsumer` + `support/InMemoryIdempotentStore`）：

```java
if (!idempotentStore.tryAcquire("rabbitmq", messageId)) {   // 1. 原子抢占去重记录
    log.info("[IdempotentConsumer] duplicate ignored: {}", messageId);
    return;
}
try {
    // 2. 执行业务；失败必须 release，否则重投会被误判为重复
} catch (RuntimeException e) {
    idempotentStore.release("rabbitmq", messageId);
    throw e;
}
```

去重表用 `ConcurrentHashMap.compute` 实现原子抢占，TTL 10 分钟：

```java
expireAtByKey.compute(key, (ignored, expireAt) -> {
    if (expireAt == null || expireAt <= now) {
        first[0] = true;                 // 首次出现，允许处理
        return now + TTL_MILLIS;
    }
    return expireAt;                     // 未过期，判定为重复
});
```

要点：

- 去重 key 必须来自**消息体**（订单号、事件 ID）。不要用 Broker 的 `messageId`：重投时它会变；
- 必须**先抢占、再处理**，否则两条重复消息会同时通过检查；处理失败要 `release`，把重试机会还回去；
- 这个实现是**进程内**的，只适合单实例 Demo：应用重启记录清空，多实例各存一份；
- 生产做法：Redis `SET demo:consumed:{id} 1 NX EX 600`（原子 + 自动过期），或数据库唯一索引 / 去重表。
  还要想清楚 Redis 挂掉时是拒绝消费还是降级放行，不能静默丢消息。

---

### 4.9 顺序：单队列 + 单消费者，生产端也要有序

先看正例（`demo.ordered.queue`，1 个消费者）：

```bash
curl -s -X POST 'http://localhost:8081/api/rabbitmq/ordered?count=10'
```

预期日志（同一个消费线程，严格递增）：

```text
[OrderedConsumer] received: step-1        # 线程 orderedConsumer-1
[OrderedConsumer] received: step-2
...
[OrderedConsumer] received: step-10
```

再看反例（`demo.unordered.queue`，4 个并发消费者）：

```bash
curl -s -X POST 'http://localhost:8081/api/rabbitmq/unordered?count=8'
```

预期日志（4 个线程交错，顺序被打乱）：

```text
[UnorderedConsumer] thread=...Container#10-3 received: step-3
[UnorderedConsumer] thread=...Container#10-1 received: step-1
[UnorderedConsumer] thread=...Container#10-2 received: step-2
[UnorderedConsumer] thread=...Container#10-4 received: step-6
```

**只把消费者改成 `concurrency = 1` 还不够。** 开启 publisher confirm 后，一个 channel 在
确认回来之前不会归还连接池，循环里的 `convertAndSend` 会落到多个 channel 上；而 Broker
只保证**同一个 channel 内**的发布顺序。实测只改消费端时，日志仍会出现 step-4/5/6 乱序。
所以发送端用 `invoke` 把整批消息绑定到同一个 channel，并在退出前等待确认：

```java
rabbitTemplate.invoke(operations -> {
    for (int i = 1; i <= total; i++) {
        operations.convertAndSend("", RabbitMQConfig.ORDERED_QUEUE, "step-" + i);
    }
    return null;
});
```

完整的顺序方案需要同时满足：

- **生产端**：同一业务 key 的消息走同一个 channel / 同一个队列，且前一条确认后再发下一条（或如上整批绑定）；
- **队列**：只用一个队列，不要给同一队列挂多个消费者（competing consumers 会轮流取消息）；
- **消费端**：`concurrency = 1`，处理失败不要跳过，否则后续消息会越过失败的那条；
- **扩容方式**：按业务 key（订单号、用户 ID）拆成多个队列各自单消费者，而不是给一个队列加线程。

---

### 4.10 积压：先暂停消费者，把消息堆起来

```bash
# 1) 暂停消费者
curl -s -X POST 'http://localhost:8081/api/rabbitmq/backlog/pause'

# 2) 发 200 条
curl -s -X POST 'http://localhost:8081/api/rabbitmq/backlog/send?count=200'

# 3) 看堆积
curl -s 'http://localhost:8081/api/rabbitmq/backlog/status'

# 4) 恢复消费
curl -s -X POST 'http://localhost:8081/api/rabbitmq/backlog/resume'
```

预期结果：

```text
暂停后            {"messageCount":"200","consumerRunning":"false"}
恢复后约 1 秒      {"messageCount":"0","consumerRunning":"true"}
```

暂停/恢复通过监听容器 id 控制：

```java
MessageListenerContainer container = listenerRegistry.getListenerContainer("backlogConsumer");
container.stop();    // 停止拉取，未 ACK 的消息会重新入队
container.start();   // 重新拉取
```

堆积量来自管理 API：`amqpAdmin.getQueueInfo(queue).getMessageCount()`，统计的是 **Ready**
（还没投递给消费者）的消息；已投递未 ACK 的要看管理台的 **Unacked**。

生产上遇到积压，按顺序排查：

1. **消费者是否还活着**：管理台 Consumers 数为 0 就是实例挂了或队列没绑上；
2. **消费是否变慢**：下游数据库 / RPC 抖动，看日志耗时和错误率，不要盲目加机器；
3. **提升消费能力**：加消费者实例、调大 `prefetch`（一次多取几条），但要先确认消息处理是幂等的；
4. **毒消息**：某条消息一直失败会反复重投，配置重试次数 + DLQ，别让它卡住整个队列；
5. **临时削峰**：先落库/落盘再异步处理，或对非核心消息做限流、降级。

---

### 4.11 持久化：重启 Broker 后消息还在吗

RabbitMQ 的持久化需要**三个条件同时成立**：队列 durable、消息 `deliveryMode=PERSISTENT`、
交换机 durable（默认交换机天然满足）。少一个都可能在重启后丢消息。

```bash
# 1) 暂停消费者，避免消息被立刻消费
curl -s -X POST 'http://localhost:8081/api/rabbitmq/persistence/pause'

# 2) 发 3 条持久化 + 3 条非持久化
curl -s -X POST 'http://localhost:8081/api/rabbitmq/persistence/send?count=3&persistent=true'
curl -s -X POST 'http://localhost:8081/api/rabbitmq/persistence/send?count=3&persistent=false'

# 3) 确认 6 条都在
curl -s 'http://localhost:8081/api/rabbitmq/persistence/status'

# 4) 重启 Broker（注意是 restart，不是 down/up）
docker compose restart rabbitmq

# 5) 再查：只剩 3 条持久化消息
curl -s 'http://localhost:8081/api/rabbitmq/persistence/status'

# 6) 恢复消费，日志只会出现 persist-1/2/3
curl -s -X POST 'http://localhost:8081/api/rabbitmq/persistence/resume'
```

实测结果：

```text
重启前  {"messageCount":"6", ...}
重启后  {"messageCount":"3", ...}      # 非持久化的 3 条没了
恢复后  {"messageCount":"0", ...} + [PersistenceConsumer] received: persist-1/2/3
```

消息属性这样设置（`ReliabilityController#sendPersistence`）：

```java
MessageProperties properties = new MessageProperties();
properties.setDeliveryMode(persistent
        ? MessageDeliveryMode.PERSISTENT      // 写磁盘
        : MessageDeliveryMode.NON_PERSISTENT);
rabbitTemplate.send("", RabbitMQConfig.PERSISTENCE_QUEUE, new Message(body, properties));
```

要点：

- `RabbitTemplate.convertAndSend` 默认已经带 `PERSISTENT`，只有显式改成 `NON_PERSISTENT` 才会丢；
- 非持久化消息在内存压力下可能被换页到磁盘，所以「重启后偶尔还在」不代表它可靠，**只有持久化才是保证**；
- 持久化只保证「消息不丢」，不保证「只消费一次」——消费者 ACK 前崩溃仍会重投，需要配合 4.8 的幂等；
- 本仓库 `docker-compose.yml` 的 RabbitMQ **没有挂数据卷**，`docker compose down` 会连容器一起删除、数据全丢；
  验证请用 `restart`。生产必须挂持久化卷，并考虑镜像队列 / Quorum Queue 做高可用。

---

## 5. 管理台怎么用（15672）

- **Overview**：连接数、消息速率，排查「消费者到底连上没有」。
- **Queues**：看 `Ready`（堆积）、`Unacked`（已投递未确认）、`Consumers`（在线消费者数）。
  点队列名 → **Get messages** 可以手动取一条消息看内容（注意选 `Ack Mode: Nack message requeue true`，
  否则消息会被你取走）。
- **Exchanges**：看绑定关系（Bindings），路由对不上时先来这里。
- **Connections / Channels**：Demo 应用应该有若干连接；一个应用通常复用少量连接、多个 channel。

一个实用技巧：排查「消息为什么没收到」时，顺序固定为
**交换机有没有消息进来 → 绑定对不对 → 队列有没有消费者 → 消费者有没有报错**。

---

## 6. 常见问题

| 现象 | 原因 | 解决 |
| --- | --- | --- |
| 启动报 `Connection refused` | RabbitMQ 没起或端口不是 5672 | `docker compose up -d rabbitmq`；`docker ps` 确认容器 Up |
| 启动报 `PRECONDITION_FAILED - inequivalent arg` | 同名队列已存在但参数不同（如 TTL、DLX） | 管理台删掉该队列后重启；改过 `RabbitMQConfig` 的队列参数时尤其常见 |
| 接口返回 `sent` 但消费者没日志 | routingKey 不匹配 / 队列没被监听 | 看日志有没有 `message returned`；管理台看绑定和 Consumers 数 |
| `Port 8081 was already in use` | 上一次启动没退干净 | `lsof -nP -iTCP:8081 -sTCP:LISTEN` 找到进程后结束 |
| 管理台登录失败 | 只允许本机登录 guest | 本 Demo 就在本机，直接用 `guest/guest` 即可 |
| 点按钮报 CORS 错误 | 测试页与接口不同源 | 已在 `WebConfig` 放行 `localhost:8081/8082`，改端口时同步修改 |

---

## 7. 从 Demo 到生产还差什么

- **队列/交换机别在应用里声明**：生产多用 IaC（Terraform / 管理台脚本）统一管理，避免多实例启动时参数冲突；
- **手动 ACK + 重试 + DLQ**：本 Demo 靠异常触发拒收，生产应配合重试次数、退避和死信告警；
- **消费幂等**：至少一次投递意味着可能重复，用业务唯一 ID + Redis/数据库去重，别用 Broker 的 messageId；
- **持久化**：`new Queue(name)` 默认 durable，但消息本身要设 `deliveryMode=PERSISTENT`（`RabbitTemplate` 默认已是持久化），并确认 Broker 挂了数据卷；
- **Confirm 的兜底**：`ack=false` 要重发，并加超时（`CorrelationData.getFuture()`），别只打日志；
- **连接与线程**：一个应用共享 `ConnectionFactory`，监听容器按队列数开线程；队列特别多时注意 channel 数；
- **多实例**：同一条队列上的消费者越多吞吐越高，但**顺序性会丢**——需要顺序就按聚合 ID 拆队列或换模型。

---

## 8. 和 RocketMQ Demo 的关系

两个模块刻意覆盖同一批场景，方便横向对比：

| 维度 | RabbitMQ（本模块） | RocketMQ（`demo-rocketmq`） |
| --- | --- | --- |
| 路由 | Exchange + binding + routingKey | Topic + Tag，订阅关系更简单 |
| 广播 | Fanout 交换机复制到多队列 | `MessageModel.BROADCASTING`，每个实例都收 |
| 顺序 | 单队列 + 单消费者保证 | 按 key 选队列，`syncSendOrderly` |
| 延迟消息 | 靠 TTL + DLX 间接实现 | 原生延迟等级（1s/5s/10s...） |
| 事务消息 | 无原生支持，需本地消息表 | 半消息 + 本地事务回查 |
| 运维 | 管理台轻量，队列级观察 | NameServer + Broker，Dashboard 看 topic/消费位点 |

想看「同样的业务语义在另一个中间件里怎么写」，直接对照阅读 `demo-rocketmq/README.md`。
