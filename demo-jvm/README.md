# demo-jvm

用「一个能实时看内存的 Web 进程」加「一组独立 main 实验」把 JVM 的三件事跑通：**运行时数据区**（每个区域存什么、怎么量、越界抛什么）、**垃圾回收**（谁在什么时候被回收、对象怎么变老怎么晋升）、**调优**（改一个参数，哪个数字会变）。全部只用 JDK 自带工具（`jcmd` / `jstat` / `jinfo` / `jmap` / `javap` / `jfr` / `-Xlog:gc*`），不装 Arthas、不装 MAT、不依赖 Docker 与数据库。

| 类型 | 场景 | 入口 | 观察点 |
| --- | --- | --- | --- |
| 核心 | 运行时数据区实时快照 | `GET /api/jvm/areas` | 7 个区域各自的 used / committed / max 从哪个 API 读出来、读不到的是什么 |
| 核心 | 分代与收集器面板 | `GET /api/jvm/pools`、`/gc`、`/threads`、`/direct` | 8 个内存池、3 个收集器 Bean 的累计计数、`-Xss × 线程数` 与 NMT 实测的差 |
| 核心 | 栈大小 → 递归深度 | `lab.sh stack-depth` | 同一方法在 256 KB / 1024 KB 栈下深度 6760 / 39528；帧大小 24 B + 固定开销 99904 B |
| 核心 | 局部变量槽位 | `lab.sh local-slots` | `javap -l` 的 `max_locals`；同槽位区间不重叠 = 编译器复用了槽 |
| 故障 | OOM 家族逐个复现 | `lab.sh oom-heap` / `-stack` / `-metaspace` / `-direct` / `-thread` / `-gcoverhead` | 每种溢出炸在哪一句、当时哪个池到顶；两个反例（thread、gcoverhead）复现不出来 |
| 对比 | 五个收集器横向对比 | `lab.sh gc-compare` | 同一份负载（校验和 `-13950`）下 STW 总时长、最长停顿、吞吐、堆峰值、Allocation Stall |
| 对比 | 单收集器 + 原始 gc.log | `lab.sh gc serial`（自带 `-Xms128m -Xmx128m`） | `gc.beans` 防呆行；`-Xlog:gc*` 里 `35M->15M(123M) 9.728ms` 怎么读 |
| 边界 | 对象年龄 / 晋升 / 大对象 | `lab.sh age` | 动态阈值被 Survivor 挤到 1；`PretenureSizeThreshold` 让对象分配即在老年代；G1 humongous 归零对照 |
| 故障 | 内存泄漏取证 | `lab.sh leak` | 泄漏版 `byte[]` 13.84→26.35 MB，修好版持平；`jcmd` 的直方图顺带触发 Full GC |
| 反例 | `String.intern()` 会不会泄漏 | `lab.sh leak --cases=intern --items=400` | 20 万条 intern 条目一次 Full GC 后掉回 84 条；真正留下的是原生内存里的桶数组 |
| 手动 | 自己起进程练 `jcmd` | `lab.sh leak-child` | stdin 喂 `GO` / `EXIT`，进程外用 `jcmd` / `jstat` / `jinfo` / `jfr` 取证 |

完整原理、图解与逐条实测见 [JVM 实战教程](../docs/jvm-guide.html)。

## 1. 启动

```bash
# 所有实验都要求先编译（lab.sh 找不到 target/classes 会直接报这一行）
./mvnw -q -pl demo-jvm compile

# 回归测试：26 条，全部起子进程跑，不会把 surefire 的 JVM 自己 OOM 掉
./mvnw -pl demo-jvm test

# 场景 1 的 Web 观测面（默认 9005，必须带 NMT 才有 native-memory 数据）
./mvnw -pl demo-jvm -am package -DskipTests
SERVER_PORT=9015 demo-jvm/scripts/lab.sh observe

# 其它场景：不带参数就是案例清单
demo-jvm/scripts/lab.sh
```

`lab.sh` 只做三件事：把这个案例需要的 JVM 旗标配齐、**把真实执行的那行命令原样打印一次**、执行并透传退出码。文档里出现的每一行命令都和它打印的完全一致，所以照着敲就能复现。OOM 类案例退出码非 0 是预期行为，不是脚本坏了。

端口约定：9001~9003 是 IDEA 里常驻的 DDD 服务，9004 是 demo-redis，本模块用 **9005**，9006 是 demo-springboot-startup，9007 是 demo-concurrency。自己验证时一律换到 9015，别和常驻进程抢端口。

环境变量与 JVM 参数（`application.yaml` 全部支持覆盖）：

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `9005` | 观测面端口 |
| `DEMO_JVM_HISTOGRAM_LIMIT` | `12` | `/api/jvm/histogram` 默认展示前 N 个类，上限 200 |
| `DEMO_JVM_MAX_ALLOCATE_MB` | `64` | `/api/jvm/allocate` 单次上限，防止把观测进程打死 |
| `-XX:NativeMemoryTracking=summary` | 由 `lab.sh observe` 自动带上 | 不给就没有 `/api/jvm/native-memory` 和 `/threads` 的 NMT 实测列 |
| `-XX:+HeapDumpOnOutOfMemoryError` | 手动加 | `lab.sh` 默认**不加**：本机 `-Xmx64m` 加它就会在当前目录掉一个 54 MB 的 `.hprof`（0.035 s），堆越大掉得越多 |

## 2. 实测结果

以下数据来自 2026-10-29 的本机实测：macOS 15.8.2、i7-8850H（12 逻辑核）、16 GB 内存、Amazon Corretto 21.0.13（`OpenJDK 64-Bit Server VM 21.0.13+10-LTS`）。观测面跑在 9015 端口。每个案例跑两轮；毫秒级指标两轮会浮动，次数/大小级指标两轮一致（下表同时给出两轮的，就是为了让读者知道该信哪个）。

### 2.1 场景 1：运行时数据区

```bash
# 一次请求返回 7 个区域 + 8 个内存池，每个区域都标明「怎么量的、读不到怎么办」
curl -s http://localhost:9015/api/jvm/areas | python3 -m json.tool | head -30
```

执行结果（节选，`--` 后是本次实测值）：

```json
{
  "process": { "pid": 49482, "javaVersion": "21", "gcAlgorithm": "G1",
               "availableCpus": 12, "physicalTotalMb": 16384.0,
               "jvmArgs": ["-XX:NativeMemoryTracking=summary"] },
  "areas": [
    { "name": "Java 虚拟机栈", "scope": "线程私有",
      "usedMb": 22.0, "committedMb": 46.13, "maxMb": null,
      "howToMeasure": "-Xss × 活跃线程数（估算）；真实值看 NMT 的 Thread 段" },
    { "name": "堆", "scope": "线程共享",
      "usedMb": 21.71, "committedMb": 154.0, "maxMb": 4096.0 },
    { "name": "方法区（HotSpot 用元空间实现）", "scope": "线程共享",
      "usedMb": 33.91, "committedMb": 34.5, "maxMb": null }
  ]
}
```

`usedMb=22.0` 是 `ThreadMXBean` 的 Java 线程数 × `-Xss` 的估算，`committedMb=46.13` 才是 NMT 实测：差了一倍多，因为 GC、JIT、监视线程这些 JVM 内部线程不出现在 `ThreadMXBean` 里。

```bash
# 栈估算 vs NMT 实测，接口里单独给一行
curl -s http://localhost:9015/api/jvm/threads
```

```json
{"threadCount":22,"daemonCount":18,"peakThreadCount":22,"totalStartedCount":25,
 "stackSizeKb":1024.0,"estimatedStackTotalMb":22.0,"nativeThreadCommittedMb":46.13,"deadlockCount":0}
```

```bash
# 分配 3 块 32 MB：前两块保留强引用，第三块不保留
curl -s -X POST 'http://localhost:9015/api/jvm/allocate?mb=32'
curl -s -X POST 'http://localhost:9015/api/jvm/allocate?mb=32'
curl -s -X POST 'http://localhost:9015/api/jvm/allocate?mb=32&retain=false'
curl -s http://localhost:9015/api/jvm/hold
```

执行结果（注意 `heapUsedMb` 的走向）：

```json
{"megabytes":32,"retain":true,"heldBlocks":1,"heldMb":32.0,"heapUsedMb":59.71}
{"megabytes":32,"retain":true,"heldBlocks":2,"heldMb":64.0,"heapUsedMb":84.02}
{"megabytes":32,"retain":false,"heldBlocks":2,"heldMb":64.0,"heapUsedMb":117.96}
{"heldBlocks":2,"heldBytes":67108864,"heldMb":64.0,"heapUsedMb":117.96}
```

```bash
# 丢弃引用：heldBlocks 这里改成「刚释放了几块」，堆使用量几乎不动
curl -s -X POST http://localhost:9015/api/jvm/allocate/clear
curl -s http://localhost:9015/api/jvm/hold
# 手动触发一次 GC，才看到 71.97 MB 真的回来了
curl -s -X POST http://localhost:9015/api/jvm/gc
```

```json
{"heldBlocks":2,"heldBytes":67108864,"heldMb":64.0,"heapUsedMb":85.91}
{"heldBlocks":0,"heldBytes":0,"heldMb":0.0,"heapUsedMb":85.91}
{"heapUsedBeforeMb":85.91,"heapUsedAfterMb":13.94,"freedMb":71.97,
 "collectorCountDelta":{"G1 Young Generation":0,"G1 Concurrent GC":0,"G1 Old Generation":1}}
```

这是本模块最想给初学者的一组数字：**释放引用 ≠ 归还内存**。第三块 32 MB 从一开始就没被保留，`retain=false` 之后堆使用量还涨到 117.96 MB——因为 GC 还没跑。

```bash
# 直方图：不带 -all 会先触发一次 Full GC，所以只看得到「可达」
curl -s 'http://localhost:9015/api/jvm/histogram?live=true&limit=3'
curl -s 'http://localhost:9015/api/jvm/histogram?live=false&limit=3'
```

```json
{"live":true,"totalMb":77.91,"entries":[
  {"rank":1,"instances":58918,"bytes":70560736,"className":"[B (java.base@21.0.13)"},
  {"rank":2,"instances":56949,"bytes":1366776,"className":"java.lang.String (java.base@21.0.13)"},
  {"rank":3,"instances":8543,"bytes":1014872,"className":"java.lang.Class (java.base@21.0.13)"}]}

{"live":false,"totalMb":80.86,"entries":[
  {"rank":1,"instances":66793,"bytes":72144336,"className":"[B (java.base@21.0.13)"},
  {"rank":2,"instances":64748,"bytes":1553952,"className":"java.lang.String (java.base@21.0.13)"},
  {"rank":3,"instances":550,"bytes":1384080,"className":"[Ljdk.internal.vm.FillerElement; (java.base@21.0.13)"}]}
```

`live=false` 多出 `FillerElement`（回收后剩下的填充块），总量 80.86 vs 77.91 MB；`[B` 的实例数 66793 → 58918，那 7875 个 `byte[]` 已经是垃圾，只是还没被搬走。

```bash
# NMT：进程真实占用的原生内存，堆之外的部分只有这里能看到
curl -s http://localhost:9015/api/jvm/native-memory | head -12
```

```
Native Memory Tracking:
Total: reserved=5801642KB, committed=419466KB
       malloc: 68838KB #150886
       mmap:   reserved=5732804KB, committed=350628KB

-                 Java Heap (reserved=4194304KB, committed=204800KB)
-                     Class (reserved=1049424KB, committed=5584KB)
-                    Thread (reserved=45181KB, committed=45181KB)   (threads #44)
-                      Code (reserved=248636KB, committed=15784KB)
-                        GC (reserved=136916KB, committed=58996KB)
```

`threads #44` 对上面 `threadCount:22`：同一个进程，两个口径差一倍，原因同上。

```bash
# 超上限的防呆：不是 500，是带原因的 400
curl -s -X POST 'http://localhost:9015/api/jvm/allocate?mb=9999'
```

```json
{"status":400,"error":"单次最多 64 MB（demo.jvm.max-allocate-mb 可调），收到 9999 MB"}
```

### 2.2 场景 2：栈

```bash
demo-jvm/scripts/lab.sh stack-depth --threadSizesKb=256,1024
```

执行结果（两轮，depth 类指标完全一致）：

```
flag.ThreadStackSizeKb=1024          # 本机 Corretto 21 x64 默认 -Xss=1024 KB
warmup.depth=652461 / 652599         # 解释执行时帧更小，所以先预热到 C2 编译后再量
main.depth=39532                     # 主线程
thread.default.depth=39528           # 新建线程：和主线程只差 4 层 →「主线程栈更大」不成立
thread.256KB.depth=6760
thread.1024KB.depth=39528
frame.bytesFitted=24.0               # 每帧拟合斜率
thread.fixedOverheadBytes=99904      # 每线程固定开销
frame.fitR2=1.0                      # depth 与 1/栈大小 线性拟合极好
```

线性模型能对上：`(1024×1024 − 99904) / 24 ≈ 39527`，实测 39528。`--threadSizesKb` 只能改新建线程；主线程的 `-Xss` 在 `main` 之前就已经生效，改不了。

**注意**：上面这组数字（24 B/帧、99904 B 固定开销）是在 `--threadSizesKb=256,1024` 下拟合出来的。如果跑默认的 5 个尺寸（128/256/512/1024/4096 KB），拟合结果会变成 48.08 B/帧、94828 B 固定开销——差了一倍。原因是 JIT 编译层不同：默认尺寸下 `recurse` 在 128 KB 栈上还没被 C2 编译（解释器帧更大），而 256/1024 两个点足够让预热把方法编译掉。本文 HTML 教程 §4.1 有详细讨论。

```bash
demo-jvm/scripts/lab.sh local-slots
```

执行结果（节选，12 个方法，`descriptorMismatch=0` 表示按描述符推算的槽数和 `javap` 全部对上）：

```
method.oneLong(long)=static desc=(J)J argSlots=2 thisSlot=0 stack=2 locals=2 bodySlots=0 vars=value@0[0+2]
method.longThenInt(long,int)=static desc=(JI)J argSlots=3 thisSlot=0 stack=4 locals=3 bodySlots=0 vars=b@0[0+5],a@2[0+5]
method.reusesSlotsInSequentialBlocks(boolean)=static locals=2 bodySlots=1 vars=first@1[6+2],flag@0[0+12],second@1[10+2]
method.keepsLocalsAlive(int)=static locals=4 bodySlots=3 vars=seed@0[0+18],a@1[4+14],b@2[8+10],c@3[12+6]
```

`long` 参数只出现一次却把下一个槽号挤掉（`a@2` 而不是 `a@1`）＝它占两槽；`first@1` 和 `second@1` 区间不重叠＝编译器复用了同一个槽。两个方法声明的局部变量个数相同，`locals` 却是 2 和 4——差别只在后面的代码是否还读得到前面的变量。

### 2.3 场景 3：OOM 家族

六种溢出各占一个进程（`lab.sh` 负责配旗标）。每种都跑了两轮，`asExpected` 两轮一致。

```bash
demo-jvm/scripts/lab.sh oom-heap
```

```
case=heap      errorClass=java.lang.OutOfMemoryError  errorMessage=Java heap space  asExpected=true
topFrame=com.example.demo.jvm.oom.OomLab.heap(OomLab.java:126)
iterationsBeforeFailure=6        # 64 MB 堆、8 MB 一块，第 6 块申请不下来
selfCountedAllocMb=48.0          # 自己数出来的只有 48 MB，剩下的被 JVM 自身占着
flag.MaxHeapSizeMb=64.0
probe.heapUsedAfterReleaseMb=55.27   # 抛完之后引用全放开，堆也没怎么回落：没 GC 干净之前它一直占着
```

| 案例 | 旗标 | 报错原文 | 关键数字 |
| --- | --- | --- | --- |
| `oom-stack` | `-Xss256k` | `java.lang.StackOverflowError`，`errorMessage=(无消息)` | `iterationsBeforeFailure=1449`；`flag.MaxHeapSizeMb=4096.0`——堆足足的，越的是栈 |
| `oom-metaspace` | `-XX:MaxMetaspaceSize=48m` | `OutOfMemoryError: Metaspace` | `iterations=7992` 个动态类，自己只数到 `selfCountedAllocMb=1.67`；`pool.MetaspaceMax=48.0 MB` |
| `oom-direct` | `-Xmx64m -XX:MaxDirectMemorySize=48m` | `Cannot reserve 16777216 bytes of direct buffer memory (allocated: 50331648, limit: 50331648)` | 3 块 16 MB 就炸；报错里直接告诉你已分配和上限 |
| `oom-thread`（反例） | `-Xmx64m` | 无异常 | 起 2000 个线程照样活著，`selfCountedAllocMb=128000.0`——**保留的引用量 ≠ 真实占用**，线程栈是按需 commit 的 |
| `oom-gcoverhead`（反例） | `-Xmx64m -XX:+UseSerialGC` | `Java heap space`，`asExpected=false` | 60826 轮才炸；HotSpot 先撞堆上限，根本没走到「GC 时间占比 98%」那条规则 |

`Metaspace` 那行值得多说一句：`-XX:MaxMetaspaceSize` 是元空间总上限，`CompressedClassSpace` 是它内部的一段（本机 `pool.CompressedClassSpaceMax=1024.0 MB`，被 `-XX:MaxMetaspaceSize=48m` 卡住后一起算 48 MB）。7992 个类只数出 1.67 MB 字节码，差的是每个 `Class` 的 klass 结构与常量池——**堆 dump 里看不到元空间的账，`jcmd <pid> VM.metaspace` 才行**（见 2.6）。

### 2.4 场景 4：收集器横向对比

```bash
demo-jvm/scripts/lab.sh gc-compare     # 父进程按变体给旗标，起五个子进程跑同一份负载
```

第 1 轮：

```
collector  cycles    stw    totalMs     maxMs    tput%   peakMB  stall   wallMs     checksum
Serial          9      9      43.37     11.58    72.89      110      0      160       -13950
Parallel       10     10      29.27      7.24    78.94       97      0      139       -13950
G1              7      7      26.78      9.18    83.57      189      0      163       -13950
ZGC             3      9       0.10      0.02    99.95      256      4      208       -13950
ZGC-Gen        12     42       0.54      0.05    99.64      152     17      150       -13950
```

第 2 轮（同一台机器、同一份代码）：

```
Serial          9      9      77.15     41.80    57.38      110      0      181       -13950
Parallel       10     10      28.63      6.63    77.46      104      0      127       -13950
G1              7      7      32.42      9.66    85.59      189      0      225       -13950
ZGC             4     12       0.17      0.03    99.95      256      3      343       -13950
ZGC-Gen        12     42       0.41      0.03    99.71      142     17      143       -13950
```

**能信的次数列**：`cycles`、`stw`、`peakMB`、`stall`、`checksum` 两轮基本不变（Serial 完全一致）。**只能看量级的时间列**：Serial 的 `totalMs` 从 43.37 跳到 77.15、`maxMs` 从 11.58 跳到 41.80——同一台机器上一轮负载不变，只是这台机器当时更忙。所以文档里所有停顿都写成区间。

稳定的结论有三条：

- `checksum=-13950` 五处相同，说明五个收集器干的是同一份活（648 MB 分配 / 40 轮 / 8 MB 常驻 / 32 KB 对象 / seed 42），对比才成立；
- ZGC 两个变体的 `totalMs` 只有 0.10~0.54 ms、`maxMs` 0.02~0.05 ms，代价是 `peakMB` 顶到 256（=`-Xmx`）并出现 `stall` 3~17 次：分配跟不上回收时，应用线程在分配点上等；
- `stw` 行数（Serial 9 / ZGC 9→12 / ZGC-Gen 42）不等于 `cycles`：ZGC 一个周期有 3 个 Pause 阶段，行数会多于周期数。

```bash
# 单个收集器 + 固定小堆，直接看原始 gc.log
JH=$(/usr/libexec/java_home)
"$JH/bin/java" -Xms128m -Xmx128m -XX:+UseSerialGC \
  "-Xlog:gc*:file=/tmp/gc-serial.log:time,uptime,level,tags" \
  -cp demo-jvm/target/classes com.example.demo.jvm.gc.GcLab --collector=serial
```

执行结果（前几次 Young GC）：

```
[0.052s][info][gc,init] Heap Min Capacity: 128M
[0.052s][info][gc,init] Heap Max Capacity: 128M
[0.166s][info][gc,start] GC(0) Pause Young (Allocation Failure)
[0.176s][info][gc]       GC(0) Pause Young (Allocation Failure) 35M->15M(123M) 9.728ms
[0.188s][info][gc]       GC(1) Pause Young (Allocation Failure) 49M->24M(123M) 8.090ms
[0.195s][info][gc]       GC(2) Pause Young (Allocation Failure) 58M->30M(123M) 4.312ms
[0.204s][info][gc]       GC(3) Pause Young (Allocation Failure) 64M->38M(123M) 5.625ms
[0.213s][info][gc]       GC(4) Pause Young (Allocation Failure) 72M->48M(123M) 6.512ms
[0.224s][info][gc]       GC(5) Pause Young (Allocation Failure) 82M->59M(123M) 7.697ms
```

`(123M)` 是 `Runtime.maxMemory()` 而不是 `-Xmx128m`——堆要按收集器自己的粒度对齐。同一参数下 `heap.xmxMb=123.75`（Serial）、`123.0`（Parallel）、`128.0`（G1 / ZGC）。回收后水位一路涨（15→24→30→38→48→59 MB），因为活对象本来就 8 MB 且还在晋升，这就是 Serial 最终必须做 Full GC 的原因。

`lab.sh gc <collector>`（`serial|parallel|g1|zgc|zgc-gen`）自带 `-Xms128m -Xmx128m`，两轮实测：

| collector | `gc.beans` 实测 | xmxMb | wallMs | gc.countInProcess | gc.timeInProcessMs |
| --- | --- | --- | --- | --- | --- |
| serial | `Copy\|MarkSweepCompact` | 123.75 | 193 / 172 | 20 | 77 / 68 |
| parallel | `PS MarkSweep\|PS Scavenge` | 123.0 | 136 | 20 | 42 |
| g1 | `G1 Young Generation\|G1 Concurrent GC\|G1 Old Generation` | 128.0 | 122 | 15 | 34 |
| zgc | `ZGC Cycles\|ZGC Pauses` | 128.0 | 148 | 24 | 45 |
| zgc-gen | `ZGC Minor Cycles\|ZGC Minor Pauses\|ZGC Major Cycles\|ZGC Major Pauses` | 128.0 | 180 | 72 | 183 |

`gc.beans` 是防呆字段：把 `-XX:+UseG1GC` 拼错时 JVM 不会报错，而是回落到默认收集器——靠这行立刻发现「参数没生效」。bean 名和 flag 名根本不是一套（`UseSerialGC` → `Copy`/`MarkSweepCompact`），所以只能读，不能猜。

### 2.5 场景 5：对象年龄与晋升

```bash
demo-jvm/scripts/lab.sh age      # 7 个变体各起一个子进程，父进程解析 gc+age=trace 日志
```

第 1 轮（`-Xms64m -Xmx64m`，40 轮 × 16 MB churn / 8 MB live）：

```
variant              youngGC cycles   ages     desurvKB  thresholds maxAge survPeakKB promotedMB  oldEndMB  humong   evacF  fullGC
serial-default            38     22     23         1088     [1, 15]      2       2175       238        38       0       0      16
serial-bigSurvivor        61     42     43         2720     [1, 15]      2       5440       309        37       0       0      19
serial-maxTenure1         38     22     22         1088         [1]      1       2176       223        38       0       0      16
serial-1m-default         43     25     25         1088     [1, 15]      1       2176       300        39       0       0      18
serial-1m-pretenure        0      0      0            0          []      0          0         0        26       0       0      26
g1-1m-humongous           83     83      2         2048        [15]      2        219        80         8      83      42      38
g1-32k-normal             99     99    101         1024     [15, 1]      2       4000       558        64       0      19       8
```

第 2 轮：Serial 五个变体**逐字符一致**（38/22/23、61/42/43、0/0/0），G1 两个变体的次数会浮（83→86、99→96），但 `humong` 83/86 对 0 这个对照关系不变。

三条能复现的结论：

1. **默认年龄上限是 15，但真正起作用的是动态阈值。** `serial-default` 的 `thresholds=[1, 15]`、`maxAge=2`：Survivor 只有 1088 KB（`desurvKB`），装不下 8 MB 常驻，于是阈值被挤到 1，对象熬过一轮就晋升。第 15 次 Young GC 才晋升的教科书描述在这台机器上根本不存在。
   ```
   [0.144s][debug][gc,age] GC(0) Desired survivor size 1114112 bytes, new threshold 1 (max threshold 15)
   [0.144s][trace][gc,age] GC(0) Age table with threshold 1 (max threshold 15)
   [0.144s][trace][gc,age] GC(0) - age   1:    2228224 bytes,    2228224 total
   [0.144s][info ][gc,heap] GC(0) DefNew: 17464K(19648K)->2176K(19648K) Eden: 17464K(17472K)->0K(17472K) From: 0K(2176K)->2176K(2176K)
   [0.144s][info ][gc,heap] GC(0) Tenured: 1056K(43712K)->14473K(43712K)   # 一次 Young GC 往老年代搬了 13 MB
   ```
   `serial-bigSurvivor` 把 `SurvivorRatio` 从 8 改成 2，`desurvKB` 从 1088 涨到 2720，`ages` 从 23 涨到 43、`survPeakKB` 从 2175 涨到 5440：装得下了，对象才真的开始变老。
2. **`MaxTenuringThreshold=1` 和默认几乎一样**（38/22/22 对 38/22/23），反过来证明默认情况下就是 1 在起作用。
3. **大对象两条路。** Serial + `PretenureSizeThreshold=1m`：`cycles=0`、`promotedMB=0`、`fullGC=26`——对象「分配即在老年代」，根本不经过 Survivor 搬运，所以晋升量读出来是 0 而不是丢数据。G1 + 2 MB region：≥1 MB 的对象算 humongous，`humong=83`，每次都是 `(G1 Humongous Allocation)` 触发；同一套参数把对象换成 32 KB（`g1-32k-normal`），`humong` 归零。
   ```
   [0.180s][info][gc,start] GC(0) Pause Young (Concurrent Start) (G1 Humongous Allocation)
   [0.181s][info][gc,heap]  GC(0) Humongous regions: 13->13
   [0.201s][info][gc,start] GC(11) Pause Young (Normal) (G1 Humongous Allocation) (Evacuation Failure) 59M->59M(64M) 0.377ms
   ```
   最后那行是 `evacF` 的来历：想搬存活对象却没地方放，`59M->59M` 一点没降，接着就会升级成 Full GC（`fullGC=42`）。

### 2.6 场景 6：内存泄漏

```bash
demo-jvm/scripts/lab.sh leak     # 每个 case 起两个进程（泄漏版 / 修好版），进程外用 jcmd 取直方图
```

执行结果（`-Xmx256m`，每阶段 200 × 64 KB；第 1、2 轮完全一致）：

```
case               fixed     live1MB    live2MB   growthMB topGrowth#1                topGrowth#2
staticCollection   false       13.84      26.35      12.51 [B +12.50                  [Ljava.lang.Object; +0.00
staticCollection   true         1.34       1.34       0.00 none                       none
threadLocal        false       13.87      26.37      12.50 [B +12.50                  [Ljava.lang.Object; +0.00
threadLocal        true         1.36       1.36       0.00 ...AQS$ConditionNode +0.00 none
unboundedCache     false       13.91      26.48      12.57 [B +12.51                  java.util.HashMap$Node +0.01
unboundedCache     true         5.35       5.35       0.00 none                       none
intern             false        1.35       1.35       0.00 [B +0.00                   java.lang.String +0.00
intern             true         1.35       1.35       0.00 [B +0.00                   java.lang.String +0.00
```

- 三个真泄漏的 case：`growthMB` ≈ 12.5 MB，正好是阶段 2 新留下的那批 `byte[]`，`topGrowth` 都是 `[B`（`byte[]` 的 JVM 名字）。
- 修好版 `live1 == live2`，但不是 0 增长字节——`unboundedCache` 修好版稳定在 5.35 MB（LRU 上限 64 × 64 KB ≈ 4 MB + 背景），这正是「修好≠不吃内存，只是不再按阶段累加」。
- `intern` 那个 case 两轮都是 0.00：JDK 21 上 intern 不涨可达堆，见下。

```bash
demo-jvm/scripts/lab.sh leak --cases=intern --items=400    # 400 × 500 = 20 万次 intern
```

```
case               fixed       P1条数GC前      P1条数GC后      P2条数GC前      P2条数GC后     桶MB(P2前)     桶MB(P2后)
intern             false        200087           84       200084           84         1.00         1.00
intern             true             88           88           88           88         0.50         0.50
```

条数在 Full GC 前 20 万、GC 后 84：**intern 表的条目是弱引用**，没人另外抓住它就收得掉。这组数字两轮逐字符一致（`200087 → 84`、`200084 → 84`、桶数组 `1.00 MB`），是本草稿里可重复性最好的一行。「intern 会内存泄漏」这句话对 JDK 6 的永久代成立，对 JDK 21 不成立。真正留在原生内存里的是桶数组（修好版 0.50 MB、泄漏版 1.00 MB），它在堆直方图里看不到，OOM 时也完全不出现。

手动取证（自己起进程，另开终端敲命令）：

```bash
demo-jvm/scripts/lab.sh leak-child --case=staticCollection
# 它停在阶段 1，等你在 stdin 输入 GO / EXIT；第一行报告里有 pid
```

同一个 pid（49917/50047 两次实测一致）在外面看：

```bash
jcmd <pid> GC.heap_info
jcmd <pid> GC.class_histogram          # 不带 -all：先触发一次 Full GC，只看可达
jinfo -flags <pid>
jstat -gcutil <pid> 500 2
```

```
 garbage-first heap   total 262144K, used 17440K          # 阶段 1
  region size 1024K, 16 young (16384K), 0 survivors (0K)
   1:          8250       13475272  [B (java.base@21.0.13)   # 12.85 MB ≈ 报告的 heldKb=12800
  S0  S1     E      O      M     CCS    YGC  YGCT    FGC  FGCT   ← jstat：FGC=1 就是上面那条 histogram 触发的
   -  -   0.00   5.99  76.17  35.92      0  0.000     1  0.015

（输入 GO，进入阶段 2）
 garbage-first heap   total 262144K, used 26980K
   1:          8450       26585672  [B (java.base@21.0.13)   # 25.35 MB ≈ heldKb=25600，+200 个 64 KB 块
```

```bash
jmap -histogram <pid>     # JDK 21：Nope。
```

```
Usage:
    jmap -clstats <pid>
    jmap -finalizerinfo <pid>
```

`jmap -histogram` 在 JDK 21 上不是有效子命令——子命令名是 `-histo`（不是 `-histogram`）。`jmap -histo <pid>` 和 `jmap -histo:live <pid>` 都还能用，但前者会触发 Full GC（和 `jcmd GC.class_histogram` 不带 `-all` 一样），后者只看可达对象。本文统一用 `jcmd`，因为它的输出更结构化、也更容易在脚本里 grep。

```bash
# JFR：profile 模板默认就带 OldObjectSample（专门给「谁在慢慢变老」用的）
jcmd <pid> JFR.start name=leak settings=profile
# ……让它继续漏……
jcmd <pid> JFR.dump name=leak filename=/tmp/leak.jfr
jfr print --events jdk.OldObjectSample /tmp/leak.jfr
```

```
jdk.OldObjectSample {
  startTime = 12:51:35.048 (2026-10-29)
  allocationTime = 12:51:33.814 (2026-10-29)
  objectSize = 256.0 kB
  objectAge = 1.23 s
  lastKnownHeapUsage = 15.9 MB
  object = [ java.util.concurrent.ConcurrentHashMap$Node[65536] ]
  arrayElements = 65536
```

实测两个坑：记录窗口里进程正阻塞等 stdin 时，`jdk.ObjectAllocationInNewTLAB` 计数是 **0**（一次分配都没发生）；另外 `settings=profile jdk.ObjectAllocationInNewTLAB=true` 会报 `Warning! The .jfc option/setting 'jdk.ObjectAllocationInNewTLAB' doesn't exist.`，事件开关要写成 `jdk.ObjectAllocationInNewTLAB#enabled=true`，改对后同一窗口拿到 12 条。

`jcmd <pid> VM.metaspace` / `VM.classloader_stats` 也能从进程外读元空间，本机实测：`Both: 2111 chunks, 35.21 MB capacity, 34.33 MB (98%) used`、`Class space: 1.00 GB reserved, 4.62 MB (<1%) committed`——类空间预留 1 GB 但只 commit 4.62 MB，所以「`-XX:MaxMetaspaceSize` 没设就会吃掉 1 GB 物理内存」是误读。

## 3. 接口清单

| 方法 | 路径 | 参数 | 返回要点 |
| --- | --- | --- | --- |
| GET | `/api/jvm/areas` | — | 7 个区域的 `used/committed/max` + `howToMeasure` + `note` |
| GET | `/api/jvm/pools` | — | 8 个内存池（含 3 段 CodeHeap、Compressed Class Space） |
| GET | `/api/jvm/gc` | — | 收集器名、`collectionCount`、`totalTimeMs`、`avgPauseMs`、所属阶段 |
| GET | `/api/jvm/threads` | — | 线程数、`stackSizeKb`、估算栈总量 vs NMT 实测、`deadlockCount` |
| GET | `/api/jvm/self` | — | `pid`（给外部 jcmd 用）、`jvmArgs`、`gcAlgorithm` |
| GET | `/api/jvm/direct` | — | `directUsedMb`、`maxMb`、`maxSource`（没设时写「JDK 按 -Xmx 作为上限」） |
| GET | `/api/jvm/hold` | — | 当前保留的块数与总量 |
| POST | `/api/jvm/allocate` | `mb`、`retain` | 分配一块；`mb` 超 `max-allocate-mb` 返回 400 |
| POST | `/api/jvm/allocate/clear` | — | 丢弃全部引用；`heldBlocks` 这里是「刚释放的块数」 |
| GET | `/api/jvm/histogram` | `live`、`limit` | `totalMb` + 类列表；`limit` 夹到 1..200 |
| GET | `/api/jvm/native-memory` | `level` | `jcmd VM.native_memory` 原文（`summary`/`detail`） |
| GET | `/api/jvm/flags` | — | `jcmd VM.flags` 原文 |
| GET | `/api/jvm/metaspace` | — | `jcmd <pid> VM.metaspace` 原文 |
| GET | `/api/jvm/classloaders` | — | `jcmd <pid> VM.classloader_stats` 原文 |
| GET | `/api/jvm/thread-dump` | — | `jcmd Thread.print` 原文 |
| GET | `/api/jvm/deadlocks` | — | `jcmd Thread.print` 里的死锁段；无事时返回 `No deadlocks found.` |
| POST | `/api/jvm/gc` | — | 前后堆使用量、`freedMb`、每个收集器的次数增量 |
| POST | `/api/jvm/heap-dump` | `name` | 落盘路径、大小、是否只含活对象 |

## 4. 代码地图

| 文件 | 职责 |
| --- | --- |
| `area/MemoryAreaService.java` | 把 MXBean / 缓冲池 / NMT 三种口径拼成一份区域快照，读不到的字段写进 `howToMeasure` 而不是留空 |
| `area/AreaSnapshot.java` | 快照的 record 结构（process / areas / pools / collectors / threads） |
| `area/HeapHolder.java` | `/allocate` 的持有器；`heldBlocks` 在 clear 后语义切换成「刚释放」，注释里写明了 |
| `controller/JvmAreaController.java` | 场景 1 的只读与分配接口 |
| `controller/JvmDiagnosticController.java` | 进程内调 `jcmd` 的接口；直方图解析成 JSON |
| `support/DiagnosticCommands.java` | 用 `HotSpotDiagnosticMXBean` 执行 `jcmd` 等价命令，不 fork 进程 |
| `support/ClassHistogram.java` | 解析 `GC.class_histogram` 文本；`-all` 与不带的差别在这里处理 |
| `support/JvmFlags.java` | 读 `-Xss`、`MaxDirectMemorySize` 等旗标，给「参数值 vs 运行时值」的对照 |
| `lab/StackDepthLab.java` | 栈大小 → 递归深度；先预热到 C2 编译，再拟合帧大小 |
| `lab/LocalSlotLab.java` + `LocalSlotSample.java` | 调 `javap -p -v -l` 解析局部变量表，样本类刻意写出复用槽位与压住活引用的两种方法 |
| `lab/ChildJvm.java` | 起子进程跑实验、收 key=value 报告；OOM 类实验必须靠它隔离 |
| `lab/LabArgs.java` / `lab/LabReport.java` | `--key=value` 参数与 `key=value` 文本输出（无第三方依赖） |
| `oom/OomLab.java` | 六种 OOM 各一个方法，报告「预期 vs 实际」并诚实标出两个阴性 |
| `gc/AllocationWorkload.java` | 确定性分配负载：LCG 填充 + 校验和，换收集器不换工作量 |
| `gc/GcLab.java` | 子进程入口：跑负载、报自己知道的数字、`gc.beans` 防呆 |
| `gc/GcCompareLab.java` | 父进程：按变体给旗标起子进程、解析 `-Xlog:gc*`、汇总对比表 |
| `gc/AgeLogParser.java` / `gc/GcLogParser.java` | gc.log 解析：停顿行、`- age N:`、`Desired survivor size`、humongous、Evacuation Failure |
| `gc/AgePromotionLab.java` | 7 个变体的年龄/晋升/大对象实验 |
| `leak/LeakLab.java` | 可被修好的泄漏进程；按阶段走，靠 stdin 的 `GO`/`EXIT` 推进 |
| `leak/LeakDetectLab.java` | 自动化取证：泄漏版 vs 修好版 + intern 表实证 |
| `scripts/lab.sh` | 案例分发器：配旗标 → 打印真实命令 → 执行 |

## 5. 坑与速查

| 想要的东西 | 正确做法 | 实测过的坑 |
| --- | --- | --- |
| 堆溢出 | `-Xmx` 给小 | 别顺手加 `-XX:+HeapDumpOnOutOfMemoryError`，会在 cwd 掉几百 MB `.hprof` |
| 栈溢出 | `-Xss256k` | `StackOverflowError.getMessage()` 是 `null`，打日志前要转成「(无消息)」 |
| 元空间溢出 | `-XX:MaxMetaspaceSize=48m` | 自己数出来的字节码 1.67 MB 就炸在 48 MB——klass 结构与常量池也是元空间的账 |
| 直接内存溢出 | `-XX:MaxDirectMemorySize=48m` | 报错在分配点，不在使用点；`Bits.reserveMemory` 抛的异常带「allocated / limit」 |
| 线程数溢出 | 靠 `-Xss` 与 OS 限制 | 本机 2000 线程起不满 `-Xmx64m` 的账；`unable to create native thread` 更多来自 OS/容器 pid 上限 |
| GC 时间上限 | `-XX:+UseGCOverheadLimit` | 默认就是开的，本机先撞堆上限，抛的是 `Java heap space` |
| 只看可达对象 | `jcmd <pid> GC.class_histogram`（不带 `-all`） | 它顺带做一次 Full GC，所以「用完就丢的对象」在结果里必然看不到 |
| 看对象年龄 | `-Xlog:gc*,gc+age=trace` | 只有 `gc*` 拿不到 `- age N:` 行，晋升年龄表属于 `gc+age=trace` |
| 对比收集器 | 校验和 + 同一 seed | `-Xmx` 不给的话按物理内存 1/4 算，收集器对比会变成「看你机器多大」 |
| 主线程栈大小 | 启动前 `-Xss` | `Thread` 构造器里的 `new Thread(null, r, name, stackSize)` 只能影响新建线程 |
| 元空间真实占用 | `jcmd <pid> VM.metaspace` | `-XX:MaxMetaspaceSize` 不限时读到的是 `-1(不限)`，不是 0 |
| 直方图上限 | `?limit=` | 会被夹到 1..200；一次返回几十万条会打爆响应 |
| 堆转储 | `jcmd <pid> GC.heap_dump` | 本机实测 `-Xmx64m` 溢出时掉 54,211,807 B（0.035 s）；观测进程 `live=true` 导出 28.24 MB。`live=true` 会先做一次 Full GC |
| `jmap -histogram` | 改 `jcmd GC.class_histogram` | JDK 21 已经没有这个子命令 |
