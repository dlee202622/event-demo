# 模块与运行约定

## 仓库结构

本仓库是 Spring Boot 4 多模块 Maven 工程，所有示例模块平铺在根目录：

```
demo/
├── pom.xml                      # <modules> 里注册每个 demo-*
├── docker-compose.yml           # MySQL / Redis / RabbitMQ / RocketMQ 等基础设施
├── api-examples.http            # 所有模块的可执行请求，按编号分节
├── docs/                        # 单文件 HTML 教程
├── demo-order-service/          # 9001
├── demo-inventory-service/      # 9002
├── demo-loyalty-service/        # 9003
├── demo-rabbitmq/
├── demo-rocketmq/
└── demo-redis/                  # 9004
```

## 端口

| 端口 | 用途 | 规则 |
|------|------|------|
| 9001–9003 | 用户常用 IDEA 常驻服务 | **不要占用，不要 kill** |
| 9004 | demo-redis | 已占用 |
| 9005+ | 新模块顺延 | 先检查端口占用 |

验证时使用独立端口（如 9014）和独立 Redis DB / MySQL schema，不要复用用户的运行数据。

## Maven

- 只用 `./mvnw`，不要假设系统 Maven 可用。
- 新模块：根 `pom.xml` 增加 `<module>`，模块 pom 继承根 `spring-boot-starter-parent`。
- 国内网络使用阿里云镜像（参考 `demo-redis/pom.xml` 的 `<repositories>`）。
- 构建命令：

```bash
./mvnw -pl demo-<topic> -am test
./mvnw -pl demo-<topic> -am package
```

## 配置

`application.yaml` 的关键项必须支持环境变量覆盖，便于隔离验证：

```yaml
server:
  port: ${SERVER_PORT:9005}
spring:
  data:
    redis:
      database: ${REDIS_DB:0}
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:mysql://localhost:3306/demo_topic?...}
demo:
  topic:
    ttl-seconds: ${DEMO_TTL_SECONDS:30}
```

启动示例：

```bash
SERVER_PORT=9014 REDIS_DB=5 \
SPRING_DATASOURCE_URL='jdbc:mysql://localhost:3306/demo_topic_verify?...' \
java -jar demo-topic/target/demo-topic-0.0.1-SNAPSHOT.jar
```

## 数据库脚本

- `schema.sql` 使用 `CREATE TABLE IF NOT EXISTS`。
- `data.sql` 使用幂等写法（`INSERT ... ON DUPLICATE KEY UPDATE` 或先删后插）。
- 演示数据要能被实验反复覆盖，不能让第二次运行结果依赖于第一次。

## 包结构

```
com.example.demo.<topic>/
├── <Topic>Application.java
├── config/          # RedisTemplate、Redisson、Web 配置
├── controller/      # HTTP 入口 + 异常处理
├── <domain>/        # 核心实现：限流器、锁、缓存、数据类型
├── repository/      # JDBC/JPA 访问
└── support/         # Lua 脚本、返回记录、工具
```

一个文件只承担一个主题；核心类超过约 300 行时优先拆分，不要靠注释掩盖职责过多。

## HTTP 约定

- 路径：`/api/<topic>/<场景>`，算法/类型作为 path 参数，例如 `/api/redis/ratelimit/{algorithm}/burst`。
- 返回使用 record，字段名直接表达含义（`allowed`、`remaining`、`retryAfterMs`）。
- 参数有默认值，范围用 `clamp` 或显式校验；错误信息列出可选值。
- 阻塞型演示接口在类注释里写明最长耗时，默认参数控制在 15 秒内。

## 测试

- 优先 Testcontainers，避免依赖本机常驻服务。
- Redis：`GenericContainer<>("redis:7.4-alpine")` + `LettuceConnectionFactory`，每个测试前 `flushDb`。
- 断言结果数量而不是只断言“成功”，例如六种限流器在 `limit=3, window=2s` 下必须恰好放行 3 次。
- 时间相关断言留容差；竞态测试先构造确定性时序，再断言最终状态。
- 测试不得修改 9001–9003 服务的数据库或队列。

## 文档与入口

- 模块 `README.md`：启动、环境变量、接口表、实测结果、代码地图。
- `docs/<topic>-guide.html`：单文件教程，规范见 `html-guide-spec.md`。
- 根 `README.md`：模块树 + 一段入口说明 + 两个链接。
- `api-examples.http`：按现有编号续写，使用文件顶部的变量（如 `@redisHost`）。

## 安全与操作

- 不执行 `git commit` / `git push`，除非用户明确要求。
- 不删除、不覆盖用户未提交的改动；实验产生的临时库表可以保留但要在文档中说明。
- 验证结束后关闭自己启动的进程；端口、Redis DB、MySQL schema 都是临时资源。
