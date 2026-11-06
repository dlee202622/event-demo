# demo-mqtt：MQTT 协议教学演示

基于 EMQX Broker 演示 MQTT 协议的核心特性：发布/订阅、QoS 三级对比、通配符订阅。

## 前置依赖

```bash
# 启动 EMQX MQTT Broker
docker compose up -d emqx

# 验证 Broker 可用（Dashboard: http://localhost:18083，账号 admin/public）
curl -s http://localhost:18083/api/v5/status | jq .
```

## 启动

```bash
# 默认端口 9005
./mvnw -pl demo-mqtt -am spring-boot:run

# 或指定隔离端口验证
SERVER_PORT=9015 java -jar demo-mqtt/target/demo-mqtt-0.0.1-SNAPSHOT.jar
```

## 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `SERVER_PORT` | 9005 | HTTP 服务端口 |
| `MQTT_BROKER_URL` | tcp://localhost:1883 | EMQX 连接地址 |
| `MQTT_CLIENT_ID_PREFIX` | demo-mqtt- | 客户端 ID 前缀 |
| `MQTT_USERNAME` | (空) | 认证用户名 |
| `MQTT_PASSWORD` | (空) | 认证密码 |
| `MQTT_CLEAN_SESSION` | true | 断开时是否清理会话 |

## 接口清单

### 场景 1：基础发布/订阅

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/mqtt/pubsub/subscribe?topic=xxx` | 订阅主题 |
| POST | `/api/mqtt/pubsub/publish?topic=xxx&payload=yyy&qos=1` | 发布消息 |
| GET | `/api/mqtt/pubsub/messages?topic=xxx` | 查询收到的消息 |
| DELETE | `/api/mqtt/pubsub/messages?topic=xxx` | 清空消息记录 |
| DELETE | `/api/mqtt/pubsub/subscribe?topic=xxx` | 取消订阅 |

### 场景 2：QoS 三级对比

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/mqtt/qos/subscribe?qos=0\|1\|2` | 订阅 QoS 测试主题 |
| POST | `/api/mqtt/qos/publish?targetQos=1&payload=xxx&publishQos=1` | 发布并指定 QoS |
| GET | `/api/mqtt/qos/messages?qos=0\|1\|2` | 查询收到的消息 |
| GET | `/api/mqtt/qos/counters` | 各 QoS 级别接收计数 |
| DELETE | `/api/mqtt/qos/reset` | 重置测试状态 |

### 场景 3：通配符订阅

> **重要**：URL 中的通配符字符需要编码：`+` → `%2B`，`#` → `%23`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/mqtt/wildcard/subscribe?topicFilter=sensor/%2B/temp` | 订阅通配符主题（`+` 匹配单层） |
| POST | `/api/mqtt/wildcard/subscribe?topicFilter=building/%23` | 订阅通配符主题（`#` 匹配多层） |
| POST | `/api/mqtt/wildcard/publish?topic=sensor/room1/temp&payload=xxx` | 发布到精确主题 |
| GET | `/api/mqtt/wildcard/messages?topicFilter=sensor/%2B/temp` | 查询匹配的消息 |
| DELETE | `/api/mqtt/wildcard/messages?topicFilter=xxx` | 清空消息记录 |
| DELETE | `/api/mqtt/wildcard/subscribe?topicFilter=xxx` | 取消订阅 |

## 实测结果

### 场景 1：基础发布/订阅

```bash
# 订阅
$ curl -X POST "http://localhost:9005/api/mqtt/pubsub/subscribe?topic=test/hello"
{"success":true,"message":"Subscribed to test/hello","clientId":"demo-mqtt-sub-685632789-175238975049455"}

# 发布
$ curl -X POST "http://localhost:9005/api/mqtt/pubsub/publish?topic=test/hello&payload=HelloMQTT&qos=1"
{"sent":true,"topic":"test/hello","qos":1,"payload":"HelloMQTT"}

# 查询收到的消息
$ curl "http://localhost:9005/api/mqtt/pubsub/messages?topic=test/hello"
[
    {
        "topic": "test/hello",
        "payload": "HelloMQTT",
        "qos": 1,
        "timestamp": 1793954561173
    }
]
```

### 场景 2：QoS 三级对比

```bash
# 订阅 QoS 测试主题（自动订阅 test/qos/0、test/qos/1、test/qos/2）
$ curl -X POST "http://localhost:9005/api/mqtt/qos/subscribe"
{"success":true,"message":"Subscribed to QoS test topic","clientId":"test/qos/1"}

# 发布消息（向 test/qos/1 发布，QoS=1）
$ curl -X POST "http://localhost:9005/api/mqtt/qos/publish?payload=QoSTest&count=3"
{
    "sent": true,
    "topic": "test/qos/1",
    "publishQos": 1,
    "targetQos": 1,
    "payload": "QoSTest"
}

# 查看各 QoS 级别的接收计数
$ curl "http://localhost:9005/api/mqtt/qos/counters"
{
    "qos-0": 0,
    "qos-1": 1,
    "qos-2": 0
}

# 查看 QoS 1 的消息详情
$ curl "http://localhost:9005/api/mqtt/qos/messages?qos=1"
[
    {
        "topic": "test/qos/1",
        "payload": "QoSTest",
        "qos": 1,
        "timestamp": 1793954664506
    }
]
```

### 场景 3：通配符订阅

> 注意：URL 中的 `+` 需编码为 `%2B`，`#` 需编码为 `%23`

```bash
# 订阅通配符主题（sensor/+/temperature 匹配 sensor/room1/temperature、sensor/room2/temperature 等）
$ curl -X POST "http://localhost:9005/api/mqtt/wildcard/subscribe?topicFilter=sensor/%2B/temperature"
{"success":true,"message":"Subscribed to wildcard filter","clientId":"demo-mqtt-wild-sensor_plus_temperature-175651725508572"}

# 发布到匹配的主题
$ curl -X POST "http://localhost:9005/api/mqtt/wildcard/publish?topic=sensor/room1/temperature&payload=22.5&qos=1"
{"sent":true,"topic":"sensor/room1/temperature","qos":1,"payload":"22.5"}

$ curl -X POST "http://localhost:9005/api/mqtt/wildcard/publish?topic=sensor/room2/temperature&payload=23.0&qos=1"
{"sent":true,"topic":"sensor/room2/temperature","qos":1,"payload":"23.0"}

# 查询收到的消息（应包含两条）
$ curl "http://localhost:9005/api/mqtt/wildcard/messages?topicFilter=sensor/%2B/temperature"
[
    {
        "topic": "sensor/room1/temperature",
        "payload": "22.5",
        "qos": 1,
        "timestamp": 1793954975246
    },
    {
        "topic": "sensor/room2/temperature",
        "payload": "23.0",
        "qos": 1,
        "timestamp": 1793954975283
    }
]

# 测试 # 通配符（building/# 匹配 building/floor1/room2/temp 等多层主题）
$ curl -X POST "http://localhost:9005/api/mqtt/wildcard/subscribe?topicFilter=building/%23"
{"success":true,"message":"Subscribed to wildcard filter","clientId":"demo-mqtt-wild-building_hash-175640799604561"}

$ curl -X POST "http://localhost:9005/api/mqtt/wildcard/publish?topic=building/floor1/room2/temp&payload=25.5&qos=1"
{"sent":true,"topic":"building/floor1/room2/temp","qos":1,"payload":"25.5"}

$ curl "http://localhost:9005/api/mqtt/wildcard/messages?topicFilter=building/%23"
[
    {
        "topic": "building/floor1/room2/temp",
        "payload": "25.5",
        "qos": 1,
        "timestamp": 1793954964315
    }
]
```

## 验证步骤（旧版，保留参考）

### 场景 1 验证步骤

```bash
# 1. 订阅
curl -X POST "http://localhost:9005/api/mqtt/pubsub/subscribe?topic=test/hello"

# 2. 发布
curl -X POST "http://localhost:9005/api/mqtt/pubsub/publish?topic=test/hello&payload=world"

# 3. 查询（应收到 payload=world 的消息）
curl "http://localhost:9005/api/mqtt/pubsub/messages?topic=test/hello"
```

### 场景 2 验证步骤

```bash
# 1. 分别订阅 QoS 0/1/2
curl -X POST "http://localhost:9005/api/mqtt/qos/subscribe?qos=0"
curl -X POST "http://localhost:9005/api/mqtt/qos/subscribe?qos=1"
curl -X POST "http://localhost:9005/api/mqtt/qos/subscribe?qos=2"

# 2. 向各主题发布消息
curl -X POST "http://localhost:9005/api/mqtt/qos/publish?targetQos=0&payload=test0"
curl -X POST "http://localhost:9005/api/mqtt/qos/publish?targetQos=1&payload=test1"
curl -X POST "http://localhost:9005/api/mqtt/qos/publish?targetQos=2&payload=test2"

# 3. 查看计数器（预期各收到 1 条）
curl "http://localhost:9005/api/mqtt/qos/counters"

# 4. QoS 降级：向 QoS 1 主题发布，但发送方用 QoS 0
curl -X POST "http://localhost:9005/api/mqtt/qos/publish?targetQos=1&payload=downgrade&publishQos=0"
# 预期：订阅者收到时 receivedQos=0
```

### 场景 3 验证步骤

```bash
# 1. 订阅单层通配符（注意 + 需编码为 %2B）
curl -X POST "http://localhost:9005/api/mqtt/wildcard/subscribe?topicFilter=sensor/%2B/temp"

# 2. 发布到匹配的主题
curl -X POST "http://localhost:9005/api/mqtt/wildcard/publish?topic=sensor/room1/temp&payload=25"

# 3. 发布到不匹配的主题（多一层）
curl -X POST "http://localhost:9005/api/mqtt/wildcard/publish?topic=sensor/room1/floor2/temp&payload=26"

# 4. 查询（应只收到第一条，注意 + 需编码为 %2B）
curl "http://localhost:9005/api/mqtt/wildcard/messages?topicFilter=sensor/%2B/temp"

# 5. 订阅多层通配符（注意 # 需编码为 %23）
curl -X POST "http://localhost:9005/api/mqtt/wildcard/subscribe?topicFilter=device/%23"

# 6. 发布到任意 device/... 主题
curl -X POST "http://localhost:9005/api/mqtt/wildcard/publish?topic=device/001/status&payload=online"

# 7. 查询（应收到，注意 # 需编码为 %23）
curl "http://localhost:9005/api/mqtt/wildcard/messages?topicFilter=device/%23"
```

## 代码地图

```
demo-mqtt/src/main/java/com/example/demo/mqtt/
├── MqttDemoApplication.java          # 启动入口
├── config/
│   ├── MqttConfig.java               # MQTT 连接与通道配置
│   └── WebConfig.java                # CORS 配置
├── controller/
│   └── MqttDemoController.java       # HTTP 接口层
└── service/
    ├── PubSubService.java            # 场景 1：基础发布/订阅
    ├── QosService.java               # 场景 2：QoS 三级对比
    └── WildcardService.java          # 场景 3：通配符订阅
```

## 核心概念速查

### QoS 三级

| QoS | 名称 | 握手次数 | 保证 | 适用场景 |
|-----|------|----------|------|----------|
| 0 | At most once | 1 | 不保证到达 | 传感器高频上报 |
| 1 | At least once | 2 | 不丢失，可能重复 | 订单状态变更 |
| 2 | Exactly once | 4 | 不丢失，不重复 | 支付通知 |

### 通配符规则

| 通配符 | 含义 | 示例 |
|--------|------|------|
| `+` | 单层通配 | `sensor/+/temp` 匹配 `sensor/room1/temp` |
| `#` | 多层通配 | `device/#` 匹配 `device/001/status` |

**注意**：实际投递的 QoS = min(发送方 QoS, 订阅方 QoS)。

## 参考

- [EMQX 文档](https://www.emqx.io/docs/zh/latest/)
- [MQTT 5.0 协议规范](https://docs.oasis-open.org/mqtt/mqtt/v5.0/mqtt-v5.0.html)
- [Spring Integration MQTT](https://docs.spring.io/spring-integration/reference/html/mqtt.html)
