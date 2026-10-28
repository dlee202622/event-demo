# demo-redis

用一组可点击的 HTTP 接口，把 Redis 最常用的四类能力跑一遍：

| 分组 | 覆盖内容 | 入口 |
| --- | --- | --- |
| 数据类型 | String / Hash / List / Set / ZSet / Bitmap / HyperLogLog / Geo / Stream（9 个独立案例） | `/api/redis/types` |
| 限流 | 固定窗口、滑动日志、双窗口计数、分桶、令牌桶、漏桶；突发、对比、窗口边界实验 | `/api/redis/ratelimit` |
| 分布式锁 | 手写 SET NX PX + Lua 释放、手写看门狗、Redisson 看门狗、leaseTime 陷阱、并发计数 | `/api/redis/lock` |
| 缓存一致性 | 缓存旁路、更新 + 延迟双删、ZSET 延迟队列、竞态确定性复现 | `/api/redis/cache` |

每个案例都会先清理自己的 key，可以反复执行；返回体里包含真实执行的 Redis 命令和结果，方便对照学习。

## 1. 启动

```bash
# Redis + MySQL（MySQL 用于延迟双删的商品表）
docker compose up -d redis mysql

# 启动模块（默认 9004，库 demo_redis 自动创建）
./mvnw -pl demo-redis spring-boot:run
```

常用环境变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `9004` | 应用端口 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_DB` | `localhost` / `6379` / `0` | Redis 连接 |
| `MYSQL_PASSWORD` | `123456` | MySQL 密码 |
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://localhost:3306/demo_redis...` | 可切换到独立验证库 |

> 端口 9001~9003 被三个 DDD 服务占用，本模块用 9004；想并行验证可再开一个实例并换端口、`REDIS_DB` 和数据库名。

## 2. 九种数据类型

```bash
# 目录
curl -s http://localhost:9004/api/redis/types

# 单个类型（string/hash/list/set/zset/bitmap/hyperloglog/geo/stream）
curl -s http://localhost:9004/api/redis/types/zset

# 一次跑完
curl -s http://localhost:9004/api/redis/types/all
```

每个返回体包含 `steps[].command`（用到的 Redis 命令）、`steps[].result`（真实结果）和 `notes`（适用场景与坑）。

几个值得对照的点：

- `hash`：更新 `city` 只写一个字段，不需要整对象覆盖；
- `zset`：`ZINCRBY` 改分数后 `ZREVRANGE ... WITHSCORES` 立刻反映排名；延迟队列也用它；
- `bitmap`：一个月的签到压缩成 31 bit，`BITCOUNT` 直接出勤天数；
- `hyperloglog`：重复 `PFADD` 不会重复计数，`PFCOUNT` 是估算值，固定约 12KB；
- `geo`：底层是 ZSet，`GEOSEARCH` 按半径过滤并带出坐标与距离；
- `stream`：`XREADGROUP` 投递后消息进入 pending，`XACK` 才真正确认。

## 3. 限流：六种算法

```bash
# 算法清单
curl -s http://localhost:9004/api/redis/ratelimit/algorithms

# 突发：limit=5，连打 12 次 → 5 放行、7 拒绝
curl -s 'http://localhost:9004/api/redis/ratelimit/sliding_log/burst?limit=5&windowSeconds=10&requests=12'

# 看 Redis 里的真实结构（string / hash / zset）
curl -s 'http://localhost:9004/api/redis/ratelimit/sliding_log/state?key=user-1'

# 六个算法跑同一段流量（间隔 100ms）
curl -s 'http://localhost:9004/api/redis/ratelimit/compare?limit=5&windowSeconds=10&requests=15&intervalMillis=100'

# 窗口边界实验：窗口结束前打满，跨过边界再打一批
curl -s 'http://localhost:9004/api/redis/ratelimit/boundary?limit=5&windowSeconds=4'
```

边界实验的实测结果（2026-10-28）：

| 算法 | 第一批 | 第二批（跨边界） | 结论 |
| --- | --- | --- | --- |
| fixed_window | 5 | **5** | 两个窗口各自计数，交界处放大了流量 |
| sliding_log | 5 | 0 | ZSET 里还记得上一批请求 |
| sliding_counter | 5 | 0 | 上一窗口按比例加权，仍然拦住 |
| sliding_bucket | 5 | 0 | 最近 N 个桶累加，仍然拦住 |

六个算法的取舍：

| 算法 | Redis 结构 | 优点 | 代价 |
| --- | --- | --- | --- |
| fixed_window | Hash（窗口编号 → 计数） | 最简单、内存最省 | 边界双倍流量 |
| sliding_log | ZSet（每次请求一条） | 最精确 | 请求量大时内存和清理成本高 |
| sliding_counter | Hash（当前 + 上一窗口） | 内存固定、近似平滑 | 临界处是估算值 |
| sliding_bucket | Hash（N 个桶） | 精度/内存可调 | 桶越细成本越高 |
| token_bucket | Hash（tokens + ts） | 允许突发、按速率补充 | 需要维护时间戳 |
| leaky_bucket | Hash（level + ts） | 输出速率恒定、削峰 | 不允许突发 |

所有判定都在 Lua 里完成，读取计数和写回是一段原子脚本，避免并发下超卖。

## 4. 分布式锁

### 4.1 手写锁：SET NX PX + Lua 校验持有者

```bash
# ttl=1.5s，业务执行 3s，不续期 → B 在 A 执行中途拿到锁
curl -s 'http://localhost:9004/api/redis/lock/simple?key=demo&ttlMs=1500&holdMs=3000&watchdog=false'

# 同样的参数，打开手写看门狗 → B 抢不到，A 释放后 B 才拿到
curl -s 'http://localhost:9004/api/redis/lock/simple?key=demo&ttlMs=1500&holdMs=4000&watchdog=true'
```

返回体里的 `events` 是关键时间线：

```
A tryLock 成功 (TTL=1500ms)
B tryLock 成功 (A 还在执行 → 临界区重叠)      ← 不续期时
A unlock 失败 (锁已不属于自己，Lua 校验失败)
```

续期版则每 `ttl/3` 执行一次 `LOCK_RENEW`，只有值仍等于自己的 holder 才 `PEXPIRE`。

### 4.2 Redisson 看门狗

```bash
# 不传 leaseTime：锁 30s 过期，后台每 10s 续期；held 12s 也不会被别人抢走
curl -s 'http://localhost:9004/api/redis/lock/redisson?key=demo&holdSeconds=12'

# 传了 leaseTime：看门狗失效，3s 后锁过期，B 进入，A 最后 unlock 失败
curl -s 'http://localhost:9004/api/redis/lock/redisson-lease?key=demo&leaseSeconds=3&holdSeconds=6'
```

`redisson` 返回的 `ttlSamples` 能看到 TTL 在 ~20s~30s 之间来回跳，说明续期确实发生了；`redisson-lease` 的 TTL 则一路降到过期。

### 4.3 并发计数：锁到底防住了什么

```bash
# 不加锁：读 → sleep(1ms) → 写，10 个线程各 20 次（期望 200），必然丢更新
curl -s 'http://localhost:9004/api/redis/lock/counter?locked=false&threads=10&iterations=20'

# 加 Redisson 锁：结果精确等于 200
curl -s 'http://localhost:9004/api/redis/lock/counter?locked=true&threads=10&iterations=20'
```

> 加锁后耗时明显变长，这是正常的：每次 `+1` 都要经历加锁、解锁两次 Redis 往返。

### 4.4 查看和清理锁

```bash
curl -s 'http://localhost:9004/api/redis/lock/state?key=demo'
curl -s -X DELETE 'http://localhost:9004/api/redis/lock?key=demo'
```

## 5. 缓存一致性：延迟双删

```bash
# 1) 首次读取：缓存未命中 → 回源 DB → 回填
curl -s http://localhost:9004/api/redis/cache/product/1

# 2) 确认缓存和数据库都有值
curl -s http://localhost:9004/api/redis/cache/product/1/inspect

# 3) 更新：DB → 删缓存 → 1000ms 后延迟再删
curl -s -X PUT http://localhost:9004/api/redis/cache/product/1 \
  -H 'Content-Type: application/json' \
  -d '{"name":"机械键盘 Pro","price":399.00}'

# 4) 观察延迟队列
curl -s http://localhost:9004/api/redis/cache/delay/pending
curl -s http://localhost:9004/api/redis/cache/delay/executed

# 5) 确定性复现「删缓存后旧值回填」
curl -s -X POST 'http://localhost:9004/api/redis/cache/race?id=99'
```

`race` 的时间线（实测）：

```
t≈0     读线程读到旧值，开始慢处理
t≈180   写线程：更新 DB + 删除缓存 + 登记延迟删除
t≈540   读线程把旧值回填缓存        ← 缓存变脏
t≈1690  延迟队列执行第二次删除      ← 脏值被清掉
t≈1710  再次读取，回源拿到新值
```

延迟队列用 ZSET：`score` 是到期时间戳，调度线程每 200ms 取 `score <= now` 的任务，先 `ZREM` 再删除，保证同一任务不会被重复执行。

> 延迟双删不是强一致：延迟时间内仍可能读到旧值；写后立即读、主从延迟、删除失败都需要额外的兜底（重试、binlog 订阅、版本号）。Demo 展示的是「竞态窗口如何产生、如何被第二次删除修正」。

## 6. 测试

```bash
./mvnw -pl demo-redis test
```

- `RateLimiterIntegrationTest`：用 Testcontainers 起临时 Redis，逐个验证六种算法「恰好放行 limit 次」、拒绝时给出重试等待、`state` 暴露的结构类型；
- `SimpleRedisLockIntegrationTest`：验证只有持有者能续期/释放、锁过期后可以被接管、旧持有者无法误删新锁。

缓存与 Redisson 场景依赖真实 MySQL / 容器和真实等待时间，放在手工验证流程里。

## 7. 代码地图

| 文件 | 职责 |
| --- | --- |
| `support/LuaScripts.java` | 六种限流、锁释放/续期、延迟队列出队的 Lua |
| `ratelimit/AbstractLuaRateLimiter.java` | 执行 Lua、解析 `允许\|剩余\|重试`、查看底层结构 |
| `ratelimit/*RateLimiter.java` | 六种算法入口，各自决定时间参数 |
| `lock/SimpleRedisLock.java` | SET NX PX + Lua 校验持有者 |
| `lock/SimpleLockService.java` | 不续期 / 手写看门狗两个时间线实验 |
| `lock/RedissonLockService.java` | Redisson 看门狗 vs leaseTime，并发计数 |
| `cache/ProductCacheService.java` | 缓存旁路、更新、竞态复现 |
| `cache/DelayTaskQueue.java` | ZSET 延迟队列 + 每 200ms 轮询 |
| `types/DataTypeService.java` | 九种数据类型的独立案例 |
| `controller/*Controller.java` | HTTP 入口与参数校验 |
| `src/test/java/...` | Testcontainers 集成测试 |
