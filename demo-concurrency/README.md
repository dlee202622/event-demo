# demo-concurrency

用 HTTP 接口和 JDK 自带的 `ThreadMXBean` 观察 Java 内置锁：互斥、可重入、AQS 队列、公平性、可中断、死锁与丢更新；进阶部分再补租约锁的看门狗、分段计数和分片 Map 锁。全部实验在内存中完成，不需要 Redis、MySQL 或 Docker。

| 类型 | 场景 | 入口 | 观察点 |
| --- | --- | --- | --- |
| 核心 | synchronized 互斥 | `/api/lock/synchronized` | 加锁时 `maxConcurrent=1`，快照出现 `BLOCKED + lockOwner`；`locked=false` 时并发为 2 |
| 核心 | ReentrantLock 重入与 AQS | `/api/lock/reentrant` | `holdCount` 1→2→3→2→1→0，队列长度、获取顺序、多 unlock 抛异常 |
| 对比 | 公平 vs 非公平 | `/api/lock/fairness` | 释放后立刻重抢：公平锁排到队尾（位置 9），非公平锁插到队首（位置 1） |
| 边界 | 锁等待期间中断 | `/api/lock/interrupt` | `synchronized` 等到锁释放才进入；`lockInterruptibly` 立即抛 `InterruptedException` |
| 故障 | AB-BA 死锁与修复 | `/api/lock/deadlock` | MXBean 检出互相等待的两个线程；固定顺序 A→B 后不再死锁 |
| 故障/对比 | 丢更新与计数吞吐 | `/api/lock/counter/lost-updates`、`/compare` | 确定性丢更新；none / synchronized / atomic / longadder 的正确性与吞吐 |
| 进阶 | 租约锁与看门狗 | `/api/lock/watchdog` | 无续期时 B 在租约到期后闯入；有续期时 B 等到 A 释放；释放后停止续期 |
| 进阶 | 分段计数 | `/api/lock/counter/striped` | 手写 Cell 数组 vs AtomicLong vs LongAdder；Cell 分布与吞吐 |
| 进阶 | 分片 Map 锁 | `/api/lock/sharded-map` | 全局锁 / 16 分片 / 热点 key 的 `maxConcurrent`、耗时与分片倾斜 |

完整原理、图解与逐条实测见 [Java 锁实战教程](../docs/java-lock-guide.html)。

## 1. 启动

```bash
# 默认端口 9007
./mvnw -pl demo-concurrency spring-boot:run

# 与常驻服务隔离验证：换端口启动
SERVER_PORT=9017 ./mvnw -pl demo-concurrency spring-boot:run

# 或者先打包再运行
./mvnw -pl demo-concurrency -am package
SERVER_PORT=9007 java -jar demo-concurrency/target/demo-concurrency-0.0.1-SNAPSHOT.jar
```

端口约定：9001~9003 是 IDEA 里常驻的 DDD 服务，9004 是 demo-redis，9005 是 demo-jvm，9006 是 demo-springboot-startup，本模块从 9007 开始。

环境变量：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `9007` | 应用端口 |
| `DEMO_LOCK_MAX_THREADS` | `32` | 单次实验允许的最大工作线程数 |
| `DEMO_LOCK_MAX_HOLD_MS` | `3000` | 持锁/阻塞类实验的最大毫秒数 |
| `DEMO_LOCK_MAX_ITERATIONS` | `200000` | 计数实验单线程最大迭代次数 |
| `DEMO_LOCK_COUNTER_ROUNDS` | `3` | 吞吐对比默认轮数（中位数统计） |
| `DEMO_LOCK_DEADLOCK_TIMEOUT_MS` | `2000` | 死锁检测的最长轮询时间 |

## 2. 九个实验的实测结果

以下数据来自 2026-10-29 的本机实测：macOS 15.8.2、12 核、OpenJDK 21.0.13，应用跑在 9017 端口，每个场景连续跑两轮。时间均相对于单次请求开始，单位毫秒。

### 2.1 synchronized 互斥

```bash
# 加锁：两个线程串行
curl -s 'http://localhost:9017/api/lock/synchronized?locked=true&holdMillis=600'

# 反例：去掉同步块
curl -s 'http://localhost:9017/api/lock/synchronized?locked=false&holdMillis=600'
```

加锁结果（节选）：

```json
{
  "locked": true,
  "maxConcurrent": 1,
  "spans": [
    {"thread": "A", "enterAtMillis": 0, "exitAtMillis": 601},
    {"thread": "B", "enterAtMillis": 602, "exitAtMillis": 1206}
  ],
  "snapshots": [
    {"threadName": "sync-3-A", "state": "TIMED_WAITING", "topFrame": "java.lang.Thread.sleep0(Native Method)"},
    {"threadName": "sync-3-B", "state": "BLOCKED", "waitingOn": "java.lang.Object@1cbfdc8c",
     "lockOwner": "sync-3-A", "blockedCount": 1,
     "topFrame": "SynchronizedLockDemo.lambda$run$0(SynchronizedLockDemo.java:70)"}
  ],
  "elapsedMillis": 1206,
  "conclusion": "synchronized 生效：两个线程串行进入，最大并发=1"
}
```

未加锁结果：`maxConcurrent=2`，两个线程的 `spans` 都从 0 开始，`elapsedMillis=605`，快照都是 `TIMED_WAITING`，没有 `lockOwner`。

结论：`synchronized` 把两个线程的临界区串成 0→601、602→1206；等待者处于 `BLOCKED`，而持有者在 `sleep` 时是 `TIMED_WAITING`。

### 2.2 ReentrantLock 可重入与 AQS 队列

```bash
curl -s 'http://localhost:9017/api/lock/reentrant?waiters=9&holdMillis=300'
```

结果（节选）：

```json
{
  "lockSteps": [
    {"action": "lock", "depth": 1, "holdCount": 1},
    {"action": "lock", "depth": 2, "holdCount": 2},
    {"action": "lock", "depth": 3, "holdCount": 3},
    {"action": "unlock", "depth": 3, "holdCount": 2},
    {"action": "unlock", "depth": 2, "holdCount": 1},
    {"action": "unlock", "depth": 1, "holdCount": 0}
  ],
  "maxQueueLength": 9,
  "queueLengthSamples": [9, 9, 9, 9, 9],
  "acquisitionOrder": ["waiter-1", "waiter-2", "waiter-3", "waiter-4", "waiter-5",
                       "waiter-6", "waiter-7", "waiter-8", "waiter-9"],
  "allWaitersAcquired": true,
  "misuse": {"caseName": "unlock-without-holding", "exception": "IllegalMonitorStateException"},
  "lockFreeAtEnd": true,
  "elapsedMillis": 425
}
```

结论：同一线程可以重入三层；9 个等待者全部入队并依次获取；请求线程未持有锁却 `unlock()` 时立刻抛 `IllegalMonitorStateException`。

### 2.3 公平锁 vs 非公平锁

```bash
# 公平锁，3 轮
curl -s 'http://localhost:9017/api/lock/fairness?fair=true&threads=8&rounds=3'

# 非公平锁，5 轮
curl -s 'http://localhost:9017/api/lock/fairness?fair=false&threads=8&rounds=5'
```

| 模式 | holder 第二次拿锁的位置 | 等待者顺序错位 | 插队轮数 |
| --- | --- | --- | --- |
| `fair=true` | `[9, 9, 9]` | `[0, 0, 0]` | 0 / 3 |
| `fair=false` | `[1, 1, 1, 1, 1]` | `[0, 0, 0, 0, 0]` | 5 / 5 |

结论：公平锁在释放后重抢时排到队尾；非公平锁跳过队列检查，直接 CAS 插到队首。等待者之间的相对顺序在两种模式下都保持 FIFO。

### 2.4 锁等待期间的中断

```bash
# synchronized：中断不可阻止进入
curl -s 'http://localhost:9017/api/lock/interrupt?mode=synchronized&holdMillis=1200&interruptAfterMillis=200'

# ReentrantLock：lockInterruptibly 立即响应
curl -s 'http://localhost:9017/api/lock/interrupt?mode=reentrant&holdMillis=1200&interruptAfterMillis=200'
```

| 模式 | 是否进入临界区 | 异常 | 中断标记 | 阻塞快照 |
| --- | --- | --- | --- | --- |
| `synchronized` | 是，1202ms 才进入 | 无 | `true` | `BLOCKED`，owner=`int-3-holder` |
| `reentrant` | 否 | `InterruptedException`，延迟 0ms | `false` | `WAITING`，owner=`int-4-holder`，栈顶 `Unsafe.park` |

结论：`synchronized` 的等待不能被中断，中断只留下标记；`lockInterruptibly()` 在 park 期间收到中断会立刻抛出异常并清除标记。

### 2.5 AB-BA 死锁与修复

```bash
# 交叉顺序：T1 先 A 后 B，T2 先 B 后 A
curl -s 'http://localhost:9017/api/lock/deadlock?fixed=false&holdMillis=100'

# 固定顺序：两个线程都先 A 后 B
curl -s 'http://localhost:9017/api/lock/deadlock?fixed=true&holdMillis=100'
```

交叉版结果（节选）：

```json
{
  "fixed": false,
  "deadlockDetected": true,
  "cleanedUp": true,
  "interrupted": true,
  "elapsedMillis": 37,
  "deadlockedThreads": [
    {"threadName": "dead-3-T1", "state": "WAITING", "lockOwner": "dead-3-T2",
     "topFrames": ["Unsafe.park", "LockSupport.park", "AbstractQueuedSynchronizer.acquire",
                   "AbstractQueuedSynchronizer.acquireInterruptibly"]},
    {"threadName": "dead-3-T2", "state": "WAITING", "lockOwner": "dead-3-T1",
     "topFrames": ["Unsafe.park", "LockSupport.park", "AbstractQueuedSynchronizer.acquire",
                   "AbstractQueuedSynchronizer.acquireInterruptibly"]}
  ],
  "timeline": [
    {"atMillis": 0, "thread": "T1", "action": "first-held"},
    {"atMillis": 0, "thread": "T2", "action": "first-held"},
    {"atMillis": 0, "thread": "T1", "action": "second-attempt"},
    {"atMillis": 0, "thread": "T2", "action": "second-attempt"},
    {"atMillis": 37, "thread": "T1", "action": "interrupted"},
    {"atMillis": 37, "thread": "T2", "action": "interrupted"}
  ]
}
```

固定顺序版：`deadlockDetected=false`、`cleanedUp=true`、`elapsedMillis=363`。时间线显示 T2 在 208ms 才拿到第一把锁，说明它在 A 上正常排队，没有形成环。

> 两轮实测中，交叉版 19~37ms 检出，固定版 321~363ms 完成，差异来自线程调度；`deadlockDetected` 与 `cleanedUp` 两轮一致。

### 2.6 丢更新与计数吞吐

```bash
# 确定性丢更新：8 线程 × 2000 次
curl -s 'http://localhost:9017/api/lock/counter/lost-updates?threads=8&iterations=2000'

# 四种写法对比：8 线程 × 100000 次 × 3 轮
curl -s 'http://localhost:9017/api/lock/counter/compare?threads=8&iterations=100000&rounds=3'

# 加到 32 线程，观察竞争加剧后的排名
curl -s 'http://localhost:9017/api/lock/counter/compare?threads=32&iterations=100000&rounds=3'
```

丢更新结果：

```json
{
  "threads": 8,
  "iterations": 2000,
  "expected": 16000,
  "actual": 2000,
  "lost": 14000,
  "elapsedMillis": 214,
  "orchestrated": true,
  "note": "两道栅栏把读改写窗口放大到必然重叠：每轮所有线程写回同一个值，只 +1"
}
```

计数对比（每种写法先预热 2 轮，再测 3 轮取中位数；以下为第 3 次请求的稳定结果）：

| 线程数 | 写法 | 三轮实际值 | 是否精确 | 中位耗时(ms) | 有效 ops/ms |
| --- | --- | --- | --- | --- | --- |
| 8 | none | 322318 / 305345 / 350754 | 否 | 4.0 | 81153.5 |
| 8 | synchronized | 800000 / 800000 / 800000 | 是 | 46.4 | 17235.8 |
| 8 | atomic | 800000 / 800000 / 800000 | 是 | 21.8 | 36712.8 |
| 8 | longadder | 800000 / 800000 / 800000 | 是 | 5.7 | 139960.8 |
| 32 | none | 728309 / 962288 / 627141 | 否 | 11.8 | 61804.3 |
| 32 | synchronized | 3200000 / 3200000 / 3200000 | 是 | 185.3 | 17264.8 |
| 32 | atomic | 3200000 / 3200000 / 3200000 | 是 | 74.8 | 42778.4 |
| 32 | longadder | 3200000 / 3200000 / 3200000 | 是 | 12.4 | 258090.8 |

结论：`none` 的结果不可信，且丢多少取决于调度；三种有保护写法在两种线程数下都精确。吞吐排序稳定为 `synchronized < atomic < longadder`，32 线程时 `LongAdder` 约为 `AtomicLong` 的 6 倍、`synchronized` 的 15 倍。

> 绝对数字依赖机器和 JIT。应用刚启动后的第一个对比请求可能明显偏慢（首轮 C2 编译尚未完成），本模块已为每种写法排除 2 轮预热；跨机器比较没有意义，只适合同机同参数对比。

### 2.7 租约锁与看门狗

```bash
# 不续期：租约 800ms，A 业务执行 2500ms
curl -s 'http://localhost:9017/api/lock/watchdog?leaseMillis=800&holdMillis=2500&watchdog=false'

# 看门狗续期：每 lease/3（约 266ms）续一次
curl -s 'http://localhost:9017/api/lock/watchdog?leaseMillis=800&holdMillis=2500&watchdog=true'
```

无看门狗结果（节选）：

```json
{
  "watchdog": false,
  "leaseMillis": 800,
  "holdMillis": 2500,
  "overlap": true,
  "renewCount": 0,
  "renewedAfterRelease": false,
  "staleReleaseIgnored": true,
  "aReleasedAtMillis": 2503,
  "bAcquiredAtMillis": 1002,
  "ownerAtEnd": null,
  "timeline": [
    {"atMillis": 2, "thread": "A", "action": "acquire", "note": "lease=800ms"},
    {"atMillis": 1002, "thread": "B", "action": "contend", "note": "开始尝试获取锁"},
    {"atMillis": 1003, "thread": "B", "action": "acquire", "note": "A 是否仍在执行=true"},
    {"atMillis": 2503, "thread": "A", "action": "release-ignored", "note": "租约已过期，当前 owner 不是 A"},
    {"atMillis": 2705, "thread": "B", "action": "release", "note": "B 正常释放"}
  ],
  "conclusion": "租约 800ms 到期后无人续期，B 在 1002ms 闯入，而 A 直到 2503ms 才结束，overlap=true"
}
```

| 模式 | `overlap` | `renewCount` | A 释放 | B 拿到锁 | 迟到 release |
| --- | --- | --- | --- | --- | --- |
| `watchdog=false` | true | 0 | 2503ms | 1002ms | 被忽略（`staleReleaseIgnored=true`） |
| `watchdog=true` | false | 9 | 2501ms | 2528ms | 不存在（A 先释放） |

有看门狗时时间线会出现 9 次 `renew`（约 274、535、803、1070、1331、1602、1867、2129、2399ms），B 的 `contend` 从 1000ms 开始每 25ms 重试一次，直到 A 在 2501ms 释放后才拿到锁。两轮实测 `overlap`、`renewedAfterRelease` 一致，B 的获取时间在 2526~2528ms 之间。

结论：租约锁的互斥不靠“持有者永远活着”，而靠“到期前持续续期”。看门狗必须在释放时停止，并且续期前要确认 owner 仍是自己、租约尚未过期，否则会把已经被别人接管的锁重新续上。Redisson 的默认行为与此一致：TTL 30s、每 10s（TTL/3）续期；显式传 `leaseTime` 则关闭看门狗。生产环境可直接看 [demo-redis 的 Redisson 案例](../demo-redis/README.md)。

### 2.8 分段计数（LongAdder 原理）

```bash
# 16 个 Cell：32 线程 × 10 万次
curl -s 'http://localhost:9017/api/lock/counter/striped?threads=32&iterations=100000&cells=16&rounds=3'

# 1 个 Cell：退化成单点 CAS
curl -s 'http://localhost:9017/api/lock/counter/striped?threads=32&iterations=100000&cells=1&rounds=3'
```

以第 3 次请求的稳定结果为例（每种写法预热 2 轮，再测 3 轮取中位数）：

| Cell 数 | 写法 | 三轮实际值 | 中位耗时(ms) | 有效 ops/ms | Cell 分布 |
| --- | --- | --- | --- | --- | --- |
| 16 | atomic | 3200000 × 3 | 65.3 | 49022.8 | — |
| 16 | striped | 3200000 × 3 | 26.4 | 121132.6 | 16 格，每格 200000 |
| 16 | longadder | 3200000 × 3 | 6.1 | 520396.0 | — |
| 1 | atomic | 3200000 × 3 | 65.3 | 48994.3 | — |
| 1 | striped | 3200000 × 3 | 93.0 | 34393.3 | 单格 3200000 |
| 1 | longadder | 3200000 × 3 | 6.8 | 472553.1 | — |

结论：32 个线程落到 16 个 Cell 上，每格恰好 2 个线程 × 10 万次 = 20 万次，热点被分散，耗时约为单 Cell 的 1/3.5；`AtomicLong` 的 CAS 全部争同一个地址，介于两者之间。单 Cell 的手写实现还多了一层数组与 `threadId` 间接寻址，因此比 `AtomicLong` 略慢——这也说明“分段”只有在确实分散到多个 Cell 时才有收益。JDK `LongAdder` 还会在竞争加剧时扩容 Cell 并做缓存行填充，所以最快。

> `sum()` 逐格读取后再相加，不是原子快照：并发更新时可能少算正在变化的 Cell。需要精确瞬时值时应使用 `AtomicLong` 或加锁。

### 2.9 分片 Map 锁

```bash
# 8 线程 × 300 次，16 个分片，锁内忙等 200µs
curl -s 'http://localhost:9017/api/lock/sharded-map?threads=8&keys=64&operations=300&shards=16&workMicros=200'
```

结果（节选）：

```json
{
  "threads": 8,
  "keys": 64,
  "operations": 300,
  "shards": 16,
  "workMicros": 200,
  "modes": [
    {"mode": "global", "shards": 1, "maxConcurrent": 1, "elapsedMillis": 503.8,
     "opsPerMillis": 4.8, "actualSum": 2400, "mapSize": 64, "exact": true,
     "shardDistribution": [2400]},
    {"mode": "sharded", "shards": 16, "maxConcurrent": 8, "elapsedMillis": 69.3,
     "opsPerMillis": 34.6, "actualSum": 2400, "mapSize": 64, "exact": true,
     "shardDistribution": [150, 113, 76, 38, 0, 75, 112, 149, 187, 188, 225, 225, 225, 225, 225, 187]},
    {"mode": "hotkey", "shards": 16, "maxConcurrent": 1, "elapsedMillis": 500.4,
     "opsPerMillis": 4.8, "actualSum": 2400, "mapSize": 1, "exact": true,
     "shardDistribution": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2400, 0, 0]}
  ],
  "conclusion": "全局锁 maxConcurrent=1；分片锁 maxConcurrent=8，耗时约为全局锁的 0.14；热点 key 模式 maxConcurrent=1，仍然退化为单锁"
}
```

结论：

- 全局锁下 `maxConcurrent` 恒为 1，8 线程完全串行，约 0.5s。
- 16 分片下 8 个线程可以同时进入各自的临界区，`maxConcurrent=8`，耗时约 69ms。
- 热点 key 模式所有操作落到同一分片，`maxConcurrent` 回到 1，耗时与全局锁相同——分片锁救不了热点。
- 分片分布并不均匀：64 个 key 的哈希没有落到第 4 个分片（计数为 0），热点 key 固定落在第 13 个分片。真实实现要关注哈希倾斜与热点。

> 一个容易踩的坑：最初所有分片共用同一张 `HashMap`，只给每个 key 配一把锁。不同分片仍会并发修改同一张表的扩容/链表结构，实测出现过 `mapSize=63`（应为 64）。修复方式是每个分片各持一张表，这也正是 ConcurrentHashMap 1.7 中 Segment 的形态：一把锁保护一张子表。测试里加了 5 轮重复运行防止回归。


## 3. 接口清单

| 方法 | 路径 | 参数与默认值 | 范围 |
| --- | --- | --- | --- |
| GET | `/api/lock` | 无 | 场景索引 |
| GET | `/api/lock/synchronized` | `locked=true`、`holdMillis=600` | `locked` 布尔；`holdMillis` 100~3000 |
| GET | `/api/lock/reentrant` | `waiters=9`、`holdMillis=300` | `waiters` 1~32；`holdMillis` 50~3000 |
| GET | `/api/lock/fairness` | `fair=true`、`threads=8`、`rounds=5` | `threads` 2~32；`rounds` 1~5 |
| GET | `/api/lock/interrupt` | `mode=synchronized`、`holdMillis=1200`、`interruptAfterMillis=200` | `mode` 为 `synchronized`/`reentrant`；`holdMillis` 300~3000；`interruptAfterMillis` 50~`holdMillis-50` |
| GET | `/api/lock/deadlock` | `fixed=false`、`holdMillis=100` | `holdMillis` 20~1000；检测上限由 `DEMO_LOCK_DEADLOCK_TIMEOUT_MS` 控制 |
| GET | `/api/lock/counter/lost-updates` | `threads=8`、`iterations=2000` | `threads` 2~32；`iterations` 100~200000 |
| GET | `/api/lock/counter/compare` | `threads=8`、`iterations=100000`、`rounds=3` | `threads` 2~32；`iterations` 1000~200000；`rounds` 1~5 |
| GET | `/api/lock/watchdog` | `leaseMillis=800`、`holdMillis=2500`、`watchdog=false` | `leaseMillis` 100~2000；`holdMillis` 500~3000 |
| GET | `/api/lock/counter/striped` | `threads=32`、`iterations=100000`、`cells=16`、`rounds=3` | `threads` 2~32；`iterations` 1000~200000；`cells` 1~64；`rounds` 1~5 |
| GET | `/api/lock/sharded-map` | `threads=8`、`keys=64`、`operations=300`、`shards=16`、`workMicros=200` | `threads` 2~32；`keys` 1~256；`operations` 1~1000；`shards` 1~64；`workMicros` 10~1000；且 `threads × operations × workMicros ≤ 2,000,000` |

参数越界或枚举值错误返回 400，例如：

```bash
curl -s 'http://localhost:9017/api/lock/interrupt?mode=abc'
curl -s 'http://localhost:9017/api/lock/counter/compare?threads=999'
curl -s 'http://localhost:9017/api/lock/sharded-map?threads=32&operations=1000&workMicros=1000'
```

```json
{"error": "mode 只能是 synchronized 或 reentrant，当前=abc", "status": 400}
{"error": "threads 必须在 2~32 之间，当前=999", "status": 400}
{"error": "threads × operations × workMicros 不能超过 2000000（约 2 秒），当前=32000000", "status": 400}
```

## 4. 测试

```bash
./mvnw -pl demo-concurrency test
```

共 20 个测试，全部不依赖外部服务：

| 测试类 | 覆盖内容 |
| --- | --- |
| `ConcurrencyDemoApplicationTest` | Spring 上下文启动 |
| `SynchronizedLockDemoTest` | 加锁 `maxConcurrent=1`、裸跑 `=2`、快照状态 |
| `ReentrantLockDemoTest` | holdCount 序列、等待者全部获取、多余 unlock 异常 |
| `FairnessDemoTest` | 公平锁位置=9、非公平锁位置=1 |
| `InterruptDemoTest` | synchronized 进入、reentrant 抛异常且不进入 |
| `DeadlockDemoTest` | 交叉版检出并清理、固定版无死锁 |
| `CounterDemoTest` | 丢更新精确到 `iterations`、四种写法精确性 |
| `WatchdogDemoTest` | 无续期时过期接管、有续期时阻止接管、释放后停止续期 |
| `StripedCounterDemoTest` | 三种写法精确、Cell 分布求和、1 格退化、2 的幂规范化 |
| `ShardedMapDemoTest` | 三种模式校验和、热点 key 并发=1、5 轮重复运行防回归 |

## 5. 代码地图

| 文件 | 职责 |
| --- | --- |
| `lock/SynchronizedLockDemo.java` | 起跑闸门 + 共享 monitor，对比加锁/裸跑的并发数与线程状态 |
| `lock/ReentrantLockDemo.java` | 三层重入、AQS 队列采样、获取顺序、故意多 unlock |
| `lock/FairnessDemo.java` | 固定入队顺序，holder 释放后立刻重抢，比较排队位置 |
| `lock/InterruptDemo.java` | 同一时序下 `synchronized` 与 `lockInterruptibly` 的中断行为 |
| `lock/DeadlockDemo.java` | 双 latch 编排 AB-BA 死锁；MXBean 检测；interrupt 清理；固定顺序修复 |
| `lock/CounterDemo.java` | 双栅栏确定性丢更新；四种写法的预热与中位数统计 |
| `lock/WatchdogDemo.java` | 内存租约锁、owner 校验、lease/3 续期与释放时停止 |
| `lock/StripedCounterDemo.java` | 手写 Cell 数组 + VarHandle，对比 AtomicLong / LongAdder |
| `lock/ShardedMapDemo.java` | 每个分片一把锁 + 一张表，对比全局锁与热点 key |
| `support/ThreadSampler.java` | `ThreadMXBean` 封装：线程快照、死锁检测、栈帧 |
| `support/Observations.java` | `Span`、`ThreadSnapshot`、`TimelineEvent` 三个观测记录 |
| `support/DemoThreads.java` | 统一创建 daemon 线程与带超时的 join |
| `support/Params.java` | 参数范围校验，错误信息带合法区间 |
| `config/DemoSettings.java` | `demo.concurrency.*` 配置绑定 |
| `controller/*Controller.java` | HTTP 入口、参数解析与场景索引 |
| `src/main/resources/application.yaml` | 端口、实验上限、日志格式 |
| `src/test/java/...` | 九个场景的确定性测试 |
