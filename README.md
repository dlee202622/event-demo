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
├── demo-rabbitmq                      RabbitMQ 基础用法示例（[文档](demo-rabbitmq/README.md)）
├── demo-rocketmq                      RocketMQ 基础用法示例（[文档](demo-rocketmq/README.md)）
├── demo-redis                         Redis 数据类型 / 限流 / 分布式锁 / 缓存一致性（[文档](demo-redis/README.md)）
├── demo-concurrency                   Java 锁 / AQS / 死锁 / 并发计数（[文档](demo-concurrency/README.md)）
├── demo-springboot-startup            Spring Boot 注解与类 / 启动链路 / 自动配置 / Bean 生命周期（[文档](demo-springboot-startup/README.md)）
└── demo-jvm                           JVM 运行时数据区 / 垃圾回收 / 调优（[文档](demo-jvm/README.md)）
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
`InventoryReservationFailedEvent`，订单服务订阅后自动取消订单。下单接口会等待
`demo.order.placement-wait-ms`（默认 1500ms）观察补偿结果：窗口内订单被取消则返回
**409**（响应体为状态 `CANCELLED` 的订单读模型），否则返回 201。订单记录仍然保留，
取消原因可在事件流中查看。

### MySQL 持久化

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

### 超时未支付（XXL-JOB）

订单超过 `demo.order.unpaid-timeout`（默认 `30m`）仍未支付时，由调度中心触发
`unpaidOrderTimeoutJob` 自动取消；取消走与人工取消相同的 `OrderCancelledEvent`，
库存服务随之释放预占，读模型变为 `CANCELLED`。

```bash
# 启动调度中心（首次会用 infra/xxl-job/tables_xxl_job.sql 初始化 xxl_job 库）
docker compose up -d xxl-job-db-init xxl-job-admin
# 控制台：http://localhost:18080 ，默认 admin / 123456
```

- 执行器 `demo-order-executor` 由订单服务自动注册（宿主机 9999 端口；调度中心在 Docker 中，回调地址为 `host.docker.internal`）；
- 执行器组的 AccessToken 填 `demo-token`，与 `xxl.job.access-token` 保持一致（3.5.0 要求非空）；
- 在调度中心新建任务：JobHandler 填 `unpaidOrderTimeoutJob`，cron 例 `0 */1 * * * ?`，也可手动执行一次；
- 单次最多处理 `demo.order.expiry-batch-size`（默认 100）笔，逐单独立事务，单笔失败不影响整批；
- 可用环境变量覆盖：`XXL_JOB_ENABLED` / `XXL_JOB_ADMIN` / `XXL_JOB_EXECUTOR_ADDRESS`。

### 消息中间件 Demo（RabbitMQ / RocketMQ）

两个独立模块，不依赖上面三个业务服务，用来单独练习中间件基础用法：

| 模块 | 应用端口 | 管理台 | 覆盖场景 | 文档 |
| --- | --- | --- | --- | --- |
| demo-rabbitmq | 8081 | <http://localhost:15672>（guest / guest） | 简单队列 / 工作队列 / Fanout / Direct / Topic / Confirm / DLX / 幂等 / 顺序 / 积压 / 持久化 / 优先级 | [demo-rabbitmq/README.md](demo-rabbitmq/README.md) |
| demo-rocketmq | 8082 | <http://localhost:19876> | 普通 / 顺序 / Tag 过滤 / 延迟 / 事务 / 广播 / 幂等 / 积压 / 持久化 / 优先级隔离 | [demo-rocketmq/README.md](demo-rocketmq/README.md) |

```bash
# RabbitMQ
docker compose up -d rabbitmq
./mvnw -pl demo-rabbitmq spring-boot:run

# RocketMQ（NameServer + Broker + Dashboard）
docker compose up -d rocketmq-namesrv rocketmq-broker rocketmq-dashboard
./mvnw -pl demo-rocketmq spring-boot:run
```

启动后打开 <http://localhost:8081/> 点按钮发消息（页面由 `demo-rabbitmq` 提供，
其中的 RocketMQ 标签页直接调用 8082 接口，所以测 RocketMQ 时两个应用都要启动；
`demo-rocketmq` 自身没有静态页面）。每个按钮对应的接口、预期日志和排障方法写在各自模块的 README 里。

想把这两个模块的原理和故障处理串起来看，可以打开图文教程 [docs/messaging-guide.html](docs/messaging-guide.html)：结构图、流程图、逐段代码和实验记录都在里面，单个 HTML 文件，浏览器直接打开即可。

### Redis Demo（数据类型 / 限流 / 分布式锁 / 缓存）

独立模块 `demo-redis`（9004），用一组 HTTP 接口覆盖四类高频场景：

| 分组 | 覆盖内容 |
| --- | --- |
| 数据类型 | String / Hash / List / Set / ZSet / Bitmap / HyperLogLog / Geo / Stream，9 个独立案例 |
| 限流 | 固定窗口 / 滑动日志 / 双窗口计数 / 滑动分桶 / 令牌桶 / 漏桶，含突发、对比、窗口边界实验 |
| 分布式锁 | 手写 SET NX PX + Lua、手写看门狗、Redisson 看门狗、leaseTime 陷阱、并发计数 |
| 缓存一致性 | 缓存旁路、延迟双删、ZSet 延迟队列、旧值回填竞态复现（接 MySQL） |

```bash
docker compose up -d redis mysql
./mvnw -pl demo-redis spring-boot:run
```

启动后从 `curl http://localhost:9004/api/redis/types/all` 开始；接口、curl 和实测输出见 [demo-redis/README.md](demo-redis/README.md)，原理图解见 [docs/redis-guide.html](docs/redis-guide.html)。

### MySQL 原理与数据结构（图文教程）

单文件 HTML 教程 [docs/mysql-guide.html](docs/mysql-guide.html)：16KB 页与 B+ 树、页分裂、回表/覆盖索引/最左前缀/索引失效、MVCC 快照读、Buffer Pool 与 redo 刷盘、行锁/间隙锁/死锁，全部结论来自本机 MySQL 8.4 实测（如覆盖索引把逻辑读从 12,043 降到 31、UUID 主键插入慢 2.1 倍、深分页游标法快 2400 倍）。实验脚本在 [docs/mysql-guide-labs/](docs/mysql-guide-labs/)，只需 `docker compose up -d mysql` 即可复现。

### Java 锁 Demo（synchronized / AQS / 死锁 / 计数）

独立模块 `demo-concurrency`（9007），纯内存运行，不依赖 Docker、MySQL 或 Redis：

| 分组 | 覆盖内容 |
| --- | --- |
| synchronized | 加锁 / 不加锁对照，BLOCKED 线程快照与栈帧 |
| ReentrantLock | 三层重入 holdCount、AQS 队列长度与入队顺序、多余 unlock |
| 公平性 | fair / non-fair 下释放后的排队位置与插队次数 |
| 中断 | monitorenter 不可中断 vs `lockInterruptibly()` 可中断 |
| 死锁 | AB-BA 确定性复现、`ThreadMXBean` 检测、固定加锁顺序修复 |
| 计数 | 丢更新复现，synchronized / AtomicLong / LongAdder 吞吐对比 |
| 看门狗 | 内存租约锁到期接管 vs lease/3 续期；释放后停止续期、迟到 release 被忽略 |
| 分段锁 | 手写 Cell 分段计数 vs AtomicLong / LongAdder；分片 Map 锁与热点 key 边界 |

```bash
./mvnw -pl demo-concurrency test
./mvnw -pl demo-concurrency spring-boot:run
```

启动后从 `curl http://localhost:9007/api/lock` 开始；接口与实测输出见 [demo-concurrency/README.md](demo-concurrency/README.md)，原理图解见 [docs/java-lock-guide.html](docs/java-lock-guide.html)。

### Spring Boot 启动原理 Demo（注解与类 / 时间线 / 模式 / 自动配置 / Bean 生命周期 / 失败分析）

独立模块 `demo-springboot-startup`（9006），纯内存运行，不依赖 Docker、MySQL 或 Redis。学习顺序从注解开始——先搞清楚「你写的每个注解点亮了哪个类」，再把注解放回启动时间轴：

| 分组 | 覆盖内容 |
| --- | --- |
| 注解与类 | `@SpringBootApplication` 反射递归拆出的元注解树、17 行「注解 → 干活的类 → 容器里多了什么」对照表、`.imports` 与 `spring.factories` 两份 SPI 实测清单（44 个候选自动配置类来自 11 份文件）、191 个 Bean 定义的来源分类、`proxyBeanMethods` 的 CGLIB 差异、自定义 `@EnableXxx` 选择器链路 |
| 启动事件时间线 | 7 个 `SpringApplicationRunListener` 阶段 + 3 个容器事件的顺序、耗时、线程；`starting` 为什么没有日志 |
| 启动模式 | NONE / SERVLET / REACTIVE / AUTO-DEDUCED 的容器类、Bean 定义数、WebServer 与端口；端口冲突异常链 |
| 自动配置 | `ConditionEvaluationReport` 接口化读取、自定义条件的中文判定、开开关前后 matched 数量对比、排除自动配置的真实后果 |
| Bean 生命周期 | constructor → destroy-method 的 17 条打点记录，`@PostConstruct` 与 BPP 的真实先后、代理替换 |
| 启动失败 | 自定义 `FailureAnalyzer` 的三行报告 vs 无人认领时的满屏堆栈（42 行 / 63 行实测对比） |

```bash
./mvnw -pl demo-springboot-startup test
./mvnw -pl demo-springboot-startup spring-boot:run
```

启动后从 `curl http://localhost:9006/api/startup/annotations`（注解对照表）或 `curl http://localhost:9006/api/startup`（场景总览）开始；接口与实测输出见 [demo-springboot-startup/README.md](demo-springboot-startup/README.md)，原理图解见 [docs/springboot-startup-guide.html](docs/springboot-startup-guide.html)。
场景 0 与场景 1~4 都在当前进程内可重复调；场景 5 需要重启进程才能观测（`DEMO_FAIL_MODE=analyzer` / `raw`），失败的应用本身没有接口。

### JVM 运行时数据区 / 垃圾回收 / 调优（场景 1–6）

独立模块 `demo-jvm`（9005），零中间件依赖，只用 JDK 自带工具（jcmd / jstat / jmap / jhsdb / javap / jfr + `-Xlog:gc*`）。六个场景覆盖运行时数据区观测、栈深度实验、OOM 家族复现、五个收集器横向对比、对象年龄与晋升、内存泄漏排查与调优前后对比。

| 场景 | 核心实验 | 关键数字 |
| --- | --- | --- |
| 1 · 运行时数据区 | `/api/jvm/areas` 一次拍全 7 个区域 + 8 个内存池 | 堆 / 元空间 / CodeCache / 直接内存各自怎么量、读不到怎么办 |
| 2 · 栈 | `lab.sh stack-depth` 递归深度 vs 栈大小 | 256K → 6760 帧，1024K → 39528 帧；每帧 24 B + 固定开销 99904 B（JIT 编译层不同会差 2×） |
| 3 · OOM 家族 | `lab.sh oom-*` 六种溢出 | 四个能稳定复现（heap / stack / metaspace / direct），两个复现不出来（thread / gcoverhead） |
| 4 · 收集器对比 | `lab.sh gc-compare` 五个收集器跑同一份活 | Serial 43~77 ms / G1 27~32 ms / ZGC 0.10~0.54 ms；checksum=-13950 五处相同 |
| 5 · 对象年龄 | `lab.sh age` 动态阈值 / 大对象 / humongous | 默认年龄上限 15，但动态阈值挤成 1；G1 region 1 MB 时 ≥ 512 KB 算 humongous |
| 6 · 内存泄漏 | `lab.sh leak` 泄漏版 vs 修好版 | `growthMB` 12.5 MB（真泄漏）vs 0.00（修好）；intern 在 JDK 21 不泄可达堆 |

```bash
./mvnw -q -pl demo-jvm compile
demo-jvm/scripts/lab.sh     # 不带参数：列出所有案例
demo-jvm/scripts/lab.sh observe     # 起 Web 观测面（端口 9015，不占 9001–9003）
```

接口与实测输出见 [demo-jvm/README.md](demo-jvm/README.md)，原理图解见 [docs/jvm-guide.html](docs/jvm-guide.html)。

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
2. **持久化**：已接入 MySQL —— starter 提供 JDBC 版 `OutboxStore` / `ProcessedEventRegistry`，
   订单聚合与读模型走 JPA，库存走悲观行锁、积分走原子 upsert，
   `@Transactional` 包住「业务写入 + outbox 写入」，投递交给中继。
3. **生产加固**：消费端失败重试与死信队列、幂等登记表加 TTL、监控 outbox 积压与消费延迟。
