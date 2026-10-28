package com.example.demo.redis.types;

import com.example.demo.redis.support.DemoResult;
import com.example.demo.redis.support.DemoStep;
import org.springframework.data.domain.Range;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 9 种数据类型的独立小案例。
 *
 * <p>每个案例使用独立 key，开头先清理，保证重复调用结果一致。
 * 案例不追求覆盖全部命令，而是回答同一个问题：<b>这种“容器形状”适合装什么数据？</b></p>
 *
 * <p>所有 key 以 {@code demo:types:} 开头，方便在 Redis 里用 {@code SCAN MATCH demo:types:*}
 * 找到并清理，不会误删业务数据。返回值统一为 {@link DemoResult}：每一步的命令、结果、
 * 一句解释，外加该类型的注意事项。</p>
 */
@Service
public class DataTypeService {

    private final StringRedisTemplate redis;

    /** 注入字符串模板：数据类型演示全部使用可读的字符串/数字，便于直接观察。 */
    public DataTypeService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 类型目录：名称用于 URL，标题用于展示，场景一句话说明“什么时候会想到它”。
     *
     * <p>{@code /api/redis/types/all} 和页面导航都基于这份列表，新增类型时只改这里
     * 和对应的 demo 方法即可。</p>
     */
    public List<TypeInfo> catalog() {
        return List.of(
                new TypeInfo("string", "String", "缓存对象、计数器、SETNX 占位"),
                new TypeInfo("hash", "Hash", "对象字段单独更新，如用户资料、购物车"),
                new TypeInfo("list", "List", "队列/栈、最新 N 条、时间线"),
                new TypeInfo("set", "Set", "去重、标签、共同好友"),
                new TypeInfo("zset", "ZSet", "排行榜、延迟队列、带权重的集合"),
                new TypeInfo("bitmap", "Bitmap", "签到、在线状态、布尔型大规模统计"),
                new TypeInfo("hyperloglog", "HyperLogLog", "UV 去重计数，固定 12KB"),
                new TypeInfo("geo", "Geo", "附近的人/店，基于 geohash"),
                new TypeInfo("stream", "Stream", "消息流 + 消费者组 + ACK")
        );
    }

    /**
     * 按类型名执行对应案例。
     *
     * @param type 目录中的 {@code name}，例如 {@code zset}
     * @return 该类型的步骤与注意事项
     * @throws IllegalArgumentException 类型未知时抛出，错误信息里附带完整目录
     */
    public DemoResult run(String type) {
        return switch (type) {
            case "string" -> stringDemo();
            case "hash" -> hashDemo();
            case "list" -> listDemo();
            case "set" -> setDemo();
            case "zset" -> zsetDemo();
            case "bitmap" -> bitmapDemo();
            case "hyperloglog" -> hyperLogLogDemo();
            case "geo" -> geoDemo();
            case "stream" -> streamDemo();
            default -> throw new IllegalArgumentException("未知类型: " + type + "，可选: " + catalog());
        };
    }

    // ------------------------------------------------------------------
    // String
    // ------------------------------------------------------------------

    /**
     * String 是最基础的容器：一段字节，可以当对象缓存、整数计数器或占位符。
     *
     * <p>演示三个最常用形态：SET/GET 缓存、INCR 计数、SETNX 抢占。
     * 注意 SETNX 只是分布式锁的起点，真实实现还必须设置过期时间并用 Lua 校验持有者。</p>
     */
    private DemoResult stringDemo() {
        String nameKey = "demo:types:string:user:1:name";
        String viewsKey = "demo:types:string:article:1001:views";
        String lockKey = "demo:types:string:lock:order:1";
        // 先清理三个 key，保证每次运行都从空状态开始（否则 INCR 会在上次结果上累加）
        redis.delete(List.of(nameKey, viewsKey, lockKey));

        List<DemoStep> steps = new ArrayList<>();
        redis.opsForValue().set(nameKey, "张三");
        steps.add(new DemoStep("SET " + nameKey + " 张三", redis.opsForValue().get(nameKey), "最普通的对象缓存"));

        // INCR 是原子操作：多实例并发自增也不会丢计数，这是 String 最适合计数器的原因
        Long views = redis.opsForValue().increment(viewsKey);
        views = redis.opsForValue().increment(viewsKey, 9);
        steps.add(new DemoStep("INCR " + viewsKey + " / INCRBY 9", views, "自增是原子操作，天然适合计数器"));

        // 第一次 SETNX 成功；第二次因为 key 已存在返回 false，这就是“抢占”语义
        Boolean first = redis.opsForValue().setIfAbsent(lockKey, "instance-A");
        Boolean second = redis.opsForValue().setIfAbsent(lockKey, "instance-B");
        steps.add(new DemoStep("SETNX " + lockKey, Map.of("第一次", first, "第二次", second),
                "SETNX 是分布式锁的起点，但要配过期时间和 Lua 释放"));

        return new DemoResult("string", "String", "缓存、计数、占位", steps, List.of(
                "单个 key 最大 512MB，但大 value 会阻塞单线程的 Redis",
                "计数器、分布式锁的 SETNX 都建立在 String 上",
                "对象缓存建议存 JSON，注意序列化版本兼容"));
    }

    // ------------------------------------------------------------------
    // Hash
    // ------------------------------------------------------------------

    /**
     * Hash 是“字段 → 值”的容器，适合字段多、每次只改少数几个字段的对象。
     *
     * <p>相比把整个对象序列化成 String，Hash 的优点是改一个字段不必读出再写回整个对象，
     * 省带宽也避免并发覆盖；缺点是字段很多时 HGETALL 会变慢。</p>
     */
    private DemoResult hashDemo() {
        String key = "demo:types:hash:user:1001";
        redis.delete(key);

        List<DemoStep> steps = new ArrayList<>();
        // 一次写入多个字段，减少往返；底层小 Hash 会用 listpack 紧凑存储
        redis.opsForHash().putAll(key, Map.of("name", "张三", "age", "28", "city", "上海"));
        steps.add(new DemoStep("HSET " + key + " name 张三 age 28 city 上海",
                toStringMap(redis.opsForHash().entries(key)), "一次写入多个字段"));

        // 只改 city，其他字段保持不变；不需要先读整个对象
        redis.opsForHash().put(key, "city", "杭州");
        steps.add(new DemoStep("HSET " + key + " city 杭州",
                redis.opsForHash().get(key, "city"), "只改一个字段，不用读出整个对象"));

        // 字段级原子自增，适合积分、库存等数值字段
        Long age = redis.opsForHash().increment(key, "age", 1);
        steps.add(new DemoStep("HINCRBY " + key + " age 1", age, "字段级原子自增"));

        return new DemoResult("hash", "Hash", "对象字段级更新", steps, List.of(
                "Hash 适合字段多、只改少数字段的场景，省带宽",
                "字段数量多时 HGETALL 会变慢，按需 HGET/HMGET",
                "小 Hash 在 Redis 内部会用 listpack 紧凑存储"));
    }

    // ------------------------------------------------------------------
    // List
    // ------------------------------------------------------------------

    /**
     * List 是链表：头尾操作 O(1)，按下标访问 O(N)。
     *
     * <p>同一组命令，从右边进左边出就是 FIFO 队列，从左边进左边出就是 LIFO 栈。
     * 它没有 ACK，进程崩溃会丢消息；需要确认机制时改用 Stream。</p>
     */
    private DemoResult listDemo() {
        String key = "demo:types:list:task:queue";
        redis.delete(key);

        List<DemoStep> steps = new ArrayList<>();
        // 右边进：RPUSH 后顺序是 task-1, task-2, task-3
        redis.opsForList().rightPushAll(key, "task-1", "task-2", "task-3");
        steps.add(new DemoStep("RPUSH " + key + " task-1 task-2 task-3",
                redis.opsForList().range(key, 0, -1), "右边进，左边出就是 FIFO 队列"));

        // 左边进：变成 task-0, task-1, task-2, task-3，再从左取就是栈
        redis.opsForList().leftPush(key, "task-0");
        steps.add(new DemoStep("LPUSH " + key + " task-0",
                redis.opsForList().range(key, 0, -1), "左边进就是栈（LIFO）"));

        // 从右边弹出一个：队列的消费端；BRPOP 是它的阻塞版本
        String popped = redis.opsForList().rightPop(key);
        steps.add(new DemoStep("RPOP " + key, Map.of("弹出", popped, "剩余", redis.opsForList().size(key)),
                "BRPOP 阻塞版本可以当简易消息队列"));

        return new DemoResult("list", "List", "队列 / 栈 / 时间线", steps, List.of(
                "List 是链表，头尾操作 O(1)，按下标访问 O(N)",
                "BRPOP 可以实现阻塞队列，但没有 ACK，进程崩溃会丢消息",
                "需要 ACK/消费者组时用 Stream，而不是 List"));
    }

    // ------------------------------------------------------------------
    // Set
    // ------------------------------------------------------------------

    /**
     * Set 是无序去重集合，价值在集合运算：交、并、差都在 Redis 内部完成。
     *
     * <p>典型场景：用户标签、共同关注、抽奖去重。元素很多时 SINTER 的计算量
     * 与集合大小相关，要注意数据量。</p>
     */
    private DemoResult setDemo() {
        String user1 = "demo:types:set:user:1:tags";
        String user2 = "demo:types:set:user:2:tags";
        redis.delete(List.of(user1, user2));

        List<DemoStep> steps = new ArrayList<>();
        // 重复元素只保留一份；“音乐”是两人的交集
        redis.opsForSet().add(user1, "篮球", "音乐", "编程");
        redis.opsForSet().add(user2, "音乐", "旅行");
        steps.add(new DemoStep("SADD " + user1 + " / SADD " + user2,
                Map.of("user1", redis.opsForSet().members(user1), "user2", redis.opsForSet().members(user2)),
                "重复元素只保留一份"));

        // 交集：共同标签 / 共同好友；不需要把两个集合拉到应用层
        Set<String> common = redis.opsForSet().intersect(user1, user2);
        steps.add(new DemoStep("SINTER " + user1 + " " + user2, common, "共同标签 / 共同好友"));

        // 并集：推荐候选、全量标签
        Set<String> union = redis.opsForSet().union(user1, user2);
        steps.add(new DemoStep("SUNION " + user1 + " " + user2, union, "并集用于推荐候选"));

        return new DemoResult("set", "Set", "去重 / 交并差", steps, List.of(
                "集合运算在 Redis 内部完成，不用把数据拉到应用层",
                "大集合的 SINTER 复杂度与元素数相关，注意数据量",
                "抽奖、去重、共同关注都是 Set 的典型场景"));
    }

    // ------------------------------------------------------------------
    // ZSet
    // ------------------------------------------------------------------

    /**
     * ZSet 是“member + score”的有序集合：member 唯一，按 score 排序，score 相同按字典序。
     *
     * <p>底层是跳表 + 哈希，范围查询和排名都是 O(logN)。同一结构可以当排行榜
     * （score=分数）或延迟队列（score=到期时间戳）。</p>
     */
    private DemoResult zsetDemo() {
        String key = "demo:types:zset:leaderboard";
        redis.delete(key);

        List<DemoStep> steps = new ArrayList<>();
        // score 决定排名：100 > 80 > 60
        redis.opsForZSet().add(key, "张三", 100);
        redis.opsForZSet().add(key, "李四", 80);
        redis.opsForZSet().add(key, "王五", 60);
        steps.add(new DemoStep("ZADD " + key + " 100 张三 80 李四 60 王五",
                tuples(redis.opsForZSet().reverseRangeWithScores(key, 0, -1)), "按分数排序"));

        // ZINCRBY 原子累加：李四 80 + 30 = 110，排名升到第一
        Double score = redis.opsForZSet().incrementScore(key, "李四", 30);
        steps.add(new DemoStep("ZINCRBY " + key + " 30 李四", score, "分数原子累加"));

        // 取 Top N：reverseRange 从高到低，0..1 是前两名
        steps.add(new DemoStep("ZREVRANGE " + key + " 0 1 WITHSCORES",
                tuples(redis.opsForZSet().reverseRangeWithScores(key, 0, 1)), "取 Top N"));

        // 换一种用法：score 存“到期毫秒时间戳”，ZRANGEBYSCORE 0 now 就是到期任务
        long dueAt = System.currentTimeMillis() + 5000;
        redis.opsForZSet().add("demo:types:zset:delay", "order-1001", dueAt);
        steps.add(new DemoStep("ZADD demo:types:zset:delay " + dueAt + " order-1001",
                Map.of("到期时间", dueAt, "用途", "score 存到期时间就是延迟队列"),
                "延迟任务：轮询 score <= now 的成员"));

        return new DemoResult("zset", "ZSet", "排行榜 / 延迟队列", steps, List.of(
                "ZSet 按 score 排序，member 唯一；相同 score 按字典序",
                "底层是跳表 + 哈希，范围查询和排名都是 O(logN)",
                "延迟队列的 score 用「到期毫秒时间戳」，用 ZRANGEBYSCORE 取到期任务"));
    }

    // ------------------------------------------------------------------
    // Bitmap
    // ------------------------------------------------------------------

    /**
     * Bitmap 不是独立类型，而是把 String 当位数组用：一个 bit 表示一个布尔状态。
     *
     * <p>31 天的签到只需要 31 bit（4 字节）；百万用户的在线状态也只要约 125KB。
     * 前提是偏移量有界：直接用 10 亿级用户 ID 当偏移会浪费巨大空间。</p>
     */
    private DemoResult bitmapDemo() {
        String signKey = "demo:types:bitmap:sign:user:1:2026-10";
        String onlineKey = "demo:types:bitmap:online:2026-10-28";
        redis.delete(List.of(signKey, onlineKey));

        List<DemoStep> steps = new ArrayList<>();
        // offset 从 0 开始：第 1 天写 offset 0，第 31 天写 offset 30
        int[] signedDays = {1, 2, 4, 8, 16};
        for (int day : signedDays) {
            redis.opsForValue().setBit(signKey, day - 1, true);
        }
        // ValueOperations 没有直接暴露 BITCOUNT，用一段小脚本读取；也可用 RedisConnection
        Long count = redis.execute(
                new DefaultRedisScript<>("return redis.call('BITCOUNT', KEYS[1])", Long.class),
                List.of(signKey));
        steps.add(new DemoStep("SETBIT " + signKey + " <day-1> 1 ×5 / BITCOUNT",
                Map.of("已签到天数", count, "签到日期", List.of(1, 2, 4, 8, 16)),
                "一个月 31 天只占 4 字节"));

        // 第 3 天没签到，对应 offset 2，读取结果为 false
        boolean day3 = Boolean.TRUE.equals(redis.opsForValue().getBit(signKey, 2));
        steps.add(new DemoStep("GETBIT " + signKey + " 2", day3, "第 3 天没签到"));

        // 用用户 ID 当 offset：1001/1002/1005 三个 bit 被置 1；BITCOUNT 直接得到在线人数
        for (int userId : new int[]{1001, 1002, 1005}) {
            redis.opsForValue().setBit(onlineKey, userId, true);
        }
        Long online = redis.execute(
                new DefaultRedisScript<>("return redis.call('BITCOUNT', KEYS[1])", Long.class),
                List.of(onlineKey));
        steps.add(new DemoStep("SETBIT " + onlineKey + " <userId> 1 / BITCOUNT", online,
                "用用户 ID 做偏移量统计在线人数"));

        return new DemoResult("bitmap", "Bitmap", "签到 / 在线状态", steps, List.of(
                "Bitmap 本质是 String，按位操作，极大节省内存",
                "偏移量必须是有界整数，用户 ID 过大会浪费空间",
                "BITCOUNT、BITOP 可以按天/按周聚合统计"));
    }

    // ------------------------------------------------------------------
    // HyperLogLog
    // ------------------------------------------------------------------

    /**
     * HyperLogLog 用固定约 12KB 估算集合基数（去重后的数量），标准误差约 0.81%。
     *
     * <p>它只回答“大约有多少个不同元素”，不能取回元素、也不保证精确值；
     * 适合 UV、搜索词去重计数，不适合需要明细或精确对账的场景。</p>
     */
    private DemoResult hyperLogLogDemo() {
        String key = "demo:types:hll:uv:2026-10-28";
        redis.delete(key);

        HyperLogLogOperations<String, String> hll = redis.opsForHyperLogLog();
        List<DemoStep> steps = new ArrayList<>();
        // 重复的 u2 只算一次，PFCOUNT 仍约为 3
        hll.add(key, "u1", "u2", "u3", "u2");
        steps.add(new DemoStep("PFADD " + key + " u1 u2 u3 u2", hll.size(key),
                "重复的 u2 只算一次，返回估算基数 3"));

        // 继续累加：u4、u5 加入，基数约为 5
        hll.add(key, "u4", "u5");
        steps.add(new DemoStep("PFADD " + key + " u4 u5", hll.size(key), "继续累加"));

        // 无论元素多少，HLL 的内存都固定在约 12KB；size 返回的是估算值
        steps.add(new DemoStep("PFCOUNT " + key, Map.of("估算 UV", hll.size(key), "内存", "约 12KB"),
                "固定内存统计海量 UV，误差约 0.81%"));

        return new DemoResult("hyperloglog", "HyperLogLog", "UV 估算", steps, List.of(
                "只适合基数估算，不能取回原始元素",
                "无论多少元素都只占约 12KB，标准误差 0.81%",
                "需要精确值或去重后的明细时不要用 HLL"));
    }

    // ------------------------------------------------------------------
    // Geo
    // ------------------------------------------------------------------

    /**
     * Geo 底层是 ZSet：score 是经纬度编码后的 geohash，因此支持按距离排序和范围查询。
     *
     * <p>适合“附近的人/店”、配送范围判断；精度受 geohash 位数限制，城市级场景足够。
     * 需要复杂地理计算（路径、多边形）时用专业 GIS，而不是 Redis Geo。</p>
     */
    private DemoResult geoDemo() {
        String key = "demo:types:geo:shops";
        redis.delete(key);

        List<DemoStep> steps = new ArrayList<>();
        // 经度在前、纬度在后：GEOADD key 经度 纬度 名称
        redis.opsForGeo().add(key, new Point(116.404, 39.915), "天安门");
        redis.opsForGeo().add(key, new Point(116.417, 39.909), "王府井");
        redis.opsForGeo().add(key, new Point(116.391, 39.907), "前门");
        redis.opsForGeo().add(key, new Point(121.473, 31.230), "上海外滩");
        steps.add(new DemoStep("GEOADD " + key + " 116.404 39.915 天安门 ...",
                "已写入 4 个地点", "经纬度 + 名称，底层是 ZSet(geohash)"));

        // 以天安门为中心找 3 公里内：上海外滩太远被排除。
        // 必须 includeCoordinates/includeDistance，否则返回的 Point/Distance 为 null
        GeoResults<GeoLocation<String>> results = redis.opsForGeo().search(key,
                GeoReference.fromCoordinate(new Point(116.404, 39.915)),
                new Distance(3, Metrics.KILOMETERS),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                        .includeCoordinates().includeDistance());
        List<Map<String, Object>> nearby = new ArrayList<>();
        for (GeoResult<GeoLocation<String>> result : results.getContent()) {
            GeoLocation<String> location = result.getContent();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", location.getName());
            item.put("longitude", location.getPoint().getX());
            item.put("latitude", location.getPoint().getY());
            // 距离单位是公里，保留两位小数便于阅读
            item.put("distanceKm", Math.round(result.getDistance().getValue() * 100) / 100.0);
            nearby.add(item);
        }
        steps.add(new DemoStep("GEOSEARCH " + key + " FROMLONLAT 116.404 39.915 BYRADIUS 3 km",
                nearby, "3 公里内的地点，上海外滩被排除"));

        // GEODIST 直接算两点距离，不需要把坐标取回应用层
        steps.add(new DemoStep("GEODIST " + key + " 天安门 王府井",
                redis.opsForGeo().distance(key, "天安门", "王府井", Metrics.KILOMETERS),
                "直接算两地距离"));

        return new DemoResult("geo", "Geo", "附近的人 / 店", steps, List.of(
                "Geo 底层是 ZSet，score 是 geohash，所以也支持 ZREM 等操作",
                "GEOSEARCH 支持圆形和矩形范围，可按距离排序",
                "精度受 geohash 位数限制，城市级场景足够"));
    }

    // ------------------------------------------------------------------
    // Stream
    // ------------------------------------------------------------------

    /**
     * Stream 是 Redis 版消息队列：持久化、消费者组、ACK、pending 列表。
     *
     * <p>与 List 的关键区别：消息不会因为被取走就消失，未 ACK 的消息留在 pending，
     * 可用 XPENDING/XCLAIM 重投。与 RabbitMQ/RocketMQ 相比更轻量，但运维能力弱。</p>
     */
    private DemoResult streamDemo() {
        String key = "demo:types:stream:orders";
        String group = "order-group";
        redis.delete(key);

        List<DemoStep> steps = new ArrayList<>();
        StreamOperations<String, String, String> stream = redis.opsForStream();
        // * 让 Redis 生成「毫秒时间戳-序号」的 ID，保证单调递增
        RecordId first = stream.add(key, Map.of(
                "orderId", "ORD-1001", "status", "CREATED"));
        stream.add(key, Map.of(
                "orderId", "ORD-1002", "status", "PAID"));
        steps.add(new DemoStep("XADD " + key + " * orderId ORD-1001 status CREATED",
                Map.of("第一条 ID", first.getValue(), "XLEN", stream.size(key)),
                "* 让 Redis 生成时间戳 ID"));

        // XRANGE - + 读取全部历史消息；生产环境应分页，避免一次拉太多
        List<MapRecord<String, String, String>> records = stream.range(key, Range.unbounded());
        steps.add(new DemoStep("XRANGE " + key + " - +", records.stream().map(this::toRecordView).toList(),
                "按 ID 范围读取历史消息"));

        try {
            // 从 0 开始建组：组内消费者能看到所有历史消息；重复建组会抛 BUSYGROUP
            stream.createGroup(key, ReadOffset.from("0"), group);
        } catch (RuntimeException ignored) {
            // 组已存在时忽略，重复调用不影响演示
        }
        // lastConsumed() 对应 ">"：只投递尚未被任何消费者领取的新消息
        List<MapRecord<String, String, String>> consumed = stream.read(
                Consumer.from(group, "consumer-1"),
                StreamOffset.create(key, ReadOffset.lastConsumed()));
        steps.add(new DemoStep("XREADGROUP GROUP " + group + " consumer-1 STREAMS " + key + " >",
                consumed == null ? List.of() : consumed.stream().map(this::toRecordView).toList(),
                "消费者组内每条消息只投递给一个消费者"));

        if (consumed != null) {
            // ACK 后消息才从 pending 列表移除；不 ACK 会被认为“已投递但未处理”
            for (MapRecord<String, String, String> record : consumed) {
                stream.acknowledge(key, group, record.getId());
            }
        }
        steps.add(new DemoStep("XACK " + key + " " + group + " <id>", "已确认",
                "ACK 之后消息才从 pending 列表移除"));

        return new DemoResult("stream", "Stream", "消息流 + 消费者组", steps, List.of(
                "Stream 是 Redis 版的消息队列：持久化、消费者组、ACK、pending 列表",
                "消费失败的消息留在 pending，可用 XPENDING/XCLAIM 重投",
                "和 RabbitMQ/RocketMQ 相比，它更轻量，但运维能力弱"));
    }

    // ------------------------------------------------------------------
    // 输出转换：保证返回的 JSON 简单可读
    // ------------------------------------------------------------------

    /** Hash 的 key 在模板里是 Object，统一转成 String，避免 JSON 字段名出现类型差异。 */
    private Map<String, Object> toStringMap(Map<Object, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((k, v) -> result.put(String.valueOf(k), v));
        return result;
    }

    /** 把 ZSet 的 TypedTuple 拍平成 {member, score}，前端不需要理解 Spring 的元组类型。 */
    private List<Map<String, Object>> tuples(Set<ZSetOperations.TypedTuple<String>> source) {
        return source.stream().map(tuple -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("member", tuple.getValue());
            item.put("score", tuple.getScore());
            return item;
        }).toList();
    }

    /** Stream 记录只暴露 ID 和消息体，隐藏底层 MapRecord 结构。 */
    private Map<String, Object> toRecordView(MapRecord<String, String, String> record) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", record.getId().getValue());
        item.put("body", record.getValue());
        return item;
    }

    /** @param name URL 中的类型名；title 展示名；scenario 一句话使用场景 */
    public record TypeInfo(String name, String title, String scenario) {
    }
}
