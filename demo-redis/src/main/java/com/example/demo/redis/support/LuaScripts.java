package com.example.demo.redis.support;

/**
 * 所有原子操作都用 Lua 表达：判断 + 写入必须在一次脚本执行里完成，否则并发下会超卖。
 *
 * <p>为什么必须用 Lua：Redis 执行脚本期间不会插入其他命令，所以「先读计数、再判断、再自增」
 * 是一个不可分割的整体。如果拆成客户端多次调用，两个请求可能同时读到「还剩 1 个名额」，
 * 然后都自增，最终放行数超过 limit。</p>
 *
 * <p>脚本约定：</p>
 * <ul>
 *   <li>{@code KEYS[1]} 是算法对应的 Redis key，{@code ARGV} 依次传参数；</li>
 *   <li>时间统一由调用方传入毫秒时间戳，方便测试里构造窗口边界；代价是依赖各实例时钟，
 *       生产环境应保证 NTP 同步，或改用脚本内的 {@code redis.call('TIME')}；</li>
 *   <li>限流脚本统一返回字符串 {@code 允许(1/0)|剩余|重试毫秒}，避免客户端对 Lua 数组/
 *       整数的映射差异；锁脚本返回 1/0 表示是否操作成功。</li>
 * </ul>
 */
public final class LuaScripts {

    private LuaScripts() {
    }

    /**
     * 固定窗口：窗口编号对齐到时间轴，Hash 字段存每个窗口的计数。
     *
     * <p>KEYS[1]=计数 Hash；ARGV[1]=now(ms)，ARGV[2]=windowMs，ARGV[3]=limit。<br>
     * 返回 {@code 1|剩余|0} 或 {@code 0|0|距窗口结束毫秒}。</p>
     *
     * <p>窗口一到就换字段，所以窗口交界处可能出现「上一窗满额 + 下一窗满额」的双倍流量。</p>
     */
    public static final String FIXED_WINDOW = """
            local now = tonumber(ARGV[1])
            local windowMs = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            -- 以 Unix 纪元为起点切窗口：同一时刻所有实例算出的编号一致
            local windowIndex = math.floor(now / windowMs)
            local current = tonumber(redis.call('HGET', KEYS[1], tostring(windowIndex)) or '0')
            if current >= limit then
                -- 本窗口已满：等到本窗口结束（now % windowMs 是已过去的毫秒数）
                return '0|0|' .. (windowMs - (now % windowMs))
            end
            -- 判断和自增在同一个脚本里完成，不会出现两个请求同时通过判断
            current = redis.call('HINCRBY', KEYS[1], tostring(windowIndex), 1)
            -- 只保留当前和上一窗口：更老的字段已不可能再被读到
            redis.call('HDEL', KEYS[1], tostring(windowIndex - 2))
            -- TTL 覆盖两个窗口：避免 key 在窗口边界瞬间消失，也方便 state() 查看上一窗
            redis.call('PEXPIRE', KEYS[1], windowMs * 2)
            return '1|' .. (limit - current) .. '|0'
            """;

    /**
     * 滑动窗口日志：ZSET 存每次请求的时间戳，按时间窗口裁剪后计数。
     *
     * <p>KEYS[1]=ZSET；ARGV[1]=now(ms)，ARGV[2]=windowMs，ARGV[3]=limit，
     * ARGV[4]=本次请求的唯一 member。<br>
     * 返回 {@code 1|剩余|0} 或 {@code 0|0|最早请求滑出窗口的等待毫秒}。</p>
     */
    public static final String SLIDING_WINDOW_LOG = """
            local now = tonumber(ARGV[1])
            local windowMs = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local member = ARGV[4]
            -- 先裁掉已经滑出窗口的请求，ZSET 里剩下的就是「最近 windowMs 内」的请求
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - windowMs)
            local count = redis.call('ZCARD', KEYS[1])
            if count >= limit then
                -- 最早的那条请求滑出窗口时才会腾出名额，因此按它计算重试时间
                local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
                local retry = 0
                if oldest[2] then
                    retry = tonumber(oldest[2]) + windowMs - now
                end
                if retry < 0 then retry = 0 end
                return '0|0|' .. retry
            end
            -- member 必须唯一：同一毫秒的多个请求如果只用时间戳，ZSET 会把它们合并成一条
            redis.call('ZADD', KEYS[1], now, member)
            -- 多留 1 秒，覆盖「请求刚写入、尚未被下一次裁剪」的边界
            redis.call('PEXPIRE', KEYS[1], windowMs + 1000)
            return '1|' .. (limit - count - 1) .. '|0'
            """;

    /**
     * 滑动窗口计数：只存当前窗口和上一窗口两个计数，按时间比例加权估算。
     *
     * <p>KEYS[1]=计数 Hash；ARGV[1]=now(ms)，ARGV[2]=windowMs，ARGV[3]=limit。<br>
     * 返回 {@code 1|剩余|0} 或 {@code 0|剩余(可能为负，由调用方截断)|重试毫秒}。</p>
     *
     * <p>它假定上一窗口的请求均匀分布，所以是估算而非精确值：内存只有两个数字，
     * 代价是高峰期可能略有偏差。</p>
     */
    public static final String SLIDING_WINDOW_COUNTER = """
            local now = tonumber(ARGV[1])
            local windowMs = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local currentWindow = math.floor(now / windowMs)
            local previousWindow = currentWindow - 1
            -- 一次取出当前和上一窗口的计数，少一次往返
            local counts = redis.call('HMGET', KEYS[1], tostring(currentWindow), tostring(previousWindow))
            local current = tonumber(counts[1]) or 0
            local previous = tonumber(counts[2]) or 0
            -- elapsed = 当前窗口已经过去的比例；上一窗口的权重随它线性衰减到 0
            local elapsed = (now % windowMs) / windowMs
            local estimated = previous * (1 - elapsed) + current
            if estimated + 1 > limit then
                -- 等到当前窗口结束，上一窗口的权重归零后再试
                local retry = windowMs - (now % windowMs)
                -- 至少 1ms，避免调用方拿到 0 后立刻空转重试
                if retry < 1 then retry = 1 end
                return '0|' .. math.floor(limit - estimated) .. '|' .. retry
            end
            redis.call('HINCRBY', KEYS[1], tostring(currentWindow), 1)
            -- 两个窗口后就只需要当前窗口，上一窗口的计数可以随 key 一起过期
            redis.call('PEXPIRE', KEYS[1], windowMs * 2)
            return '1|' .. math.floor(limit - estimated - 1) .. '|0'
            """;

    /**
     * 滑动窗口分桶：窗口切成 N 个桶，每次累加最近 N 个桶。
     *
     * <p>KEYS[1]=桶 Hash；ARGV[1]=now(ms)，ARGV[2]=windowMs，ARGV[3]=limit，
     * ARGV[4]=桶数量。<br>
     * 返回 {@code 1|剩余|0} 或 {@code 0|0|最早腾出名额的桶边界毫秒}。</p>
     *
     * <p>桶越多越接近滑动日志，内存和计算量也越大；10 个桶意味着精度约为窗口的 1/10。</p>
     */
    public static final String SLIDING_WINDOW_BUCKET = """
            local now = tonumber(ARGV[1])
            local windowMs = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local buckets = tonumber(ARGV[4])
            -- 每个桶的宽度；窗口很小时兜底为 1ms，避免除零
            local bucketMs = math.floor(windowMs / buckets)
            if bucketMs < 1 then bucketMs = 1 end
            -- 桶编号同样以纪元对齐，保证多实例一致
            local current = math.floor(now / bucketMs)
            local sum = 0
            -- 累加当前桶和之前 buckets-1 个桶，覆盖最近一个完整窗口
            for i = 0, buckets - 1 do
                local value = redis.call('HGET', KEYS[1], tostring(current - i))
                if value then sum = sum + tonumber(value) end
            end
            if sum >= limit then
                -- 需要腾出 overflow 个名额；从最旧的桶开始累计，找到第一个能满足的桶边界
                local overflow = sum - limit + 1
                local freed = 0
                local retry = bucketMs
                for i = buckets - 1, 0, -1 do
                    local index = current - i
                    local value = tonumber(redis.call('HGET', KEYS[1], tostring(index)) or '0')
                    freed = freed + value
                    if freed >= overflow then
                        -- 该桶整体滑出窗口的时刻，就是最早能再放行的时间
                        retry = (index + 1) * bucketMs - now
                        break
                    end
                end
                if retry < 1 then retry = 1 end
                return '0|0|' .. retry
            end
            redis.call('HINCRBY', KEYS[1], tostring(current), 1)
            -- 删除已经滑出窗口的桶，避免 Hash 无限增长
            redis.call('HDEL', KEYS[1], tostring(current - buckets))
            redis.call('PEXPIRE', KEYS[1], windowMs * 2)
            return '1|' .. (limit - sum - 1) .. '|0'
            """;

    /**
     * 令牌桶：按时间补充令牌，允许突发到桶容量。
     *
     * <p>KEYS[1]=状态 Hash；ARGV[1]=now(ms)，ARGV[2]=容量，ARGV[3]=每秒补充数，
     * ARGV[4]=windowMs（只用于 TTL）。<br>
     * 返回 {@code 1|剩余|0} 或 {@code 0|剩余|等待毫秒}。</p>
     */
    public static final String TOKEN_BUCKET = """
            local now = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local refillPerSec = tonumber(ARGV[3])
            local windowMs = tonumber(ARGV[4])
            local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            -- 首次访问：桶是满的，时间戳取当前时刻，因此允许一次性突发 capacity 个请求
            if tokens == nil then tokens = capacity end
            if ts == nil then ts = now end
            -- 惰性补充：不启动定时器，每次请求时按经过时间补令牌；max(0) 防止时钟回拨
            local elapsed = math.max(0, now - ts) / 1000
            tokens = math.min(capacity, tokens + elapsed * refillPerSec)
            local allowed = 0
            local retry = 0
            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            else
                -- 还差多少秒才能攒够 1 个令牌
                retry = math.ceil((1 - tokens) / refillPerSec * 1000)
            end
            -- 同时写回令牌数和时间戳：两者必须一起更新，否则下次会重复补充
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            -- 空闲桶至少保留 60 秒，避免频繁创建导致每次都从「满桶」开始
            redis.call('PEXPIRE', KEYS[1], math.max(windowMs * 2, 60000))
            return allowed .. '|' .. math.floor(tokens) .. '|' .. retry
            """;

    /**
     * 漏桶：请求先入桶，再以固定速率漏出；桶满即拒绝。
     *
     * <p>KEYS[1]=状态 Hash；ARGV[1]=now(ms)，ARGV[2]=容量，ARGV[3]=每秒漏出数，
     * ARGV[4]=windowMs（只用于 TTL）。<br>
     * 返回 {@code 1|剩余容量|0} 或 {@code 0|剩余容量(0)|等待毫秒}。</p>
     *
     * <p>注意：本实现只维护「水位」并在满时拒绝，不会真的让请求排队等待；
     * 它的价值是保证被接受的请求以固定速率流出，而不是削峰填谷。</p>
     */
    public static final String LEAKY_BUCKET = """
            local now = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local leakPerSec = tonumber(ARGV[3])
            local windowMs = tonumber(ARGV[4])
            local data = redis.call('HMGET', KEYS[1], 'level', 'ts')
            local level = tonumber(data[1]) or 0
            local ts = tonumber(data[2]) or now
            -- 惰性漏出：按经过时间降低水位，不依赖后台任务
            local elapsed = math.max(0, now - ts) / 1000
            level = math.max(0, level - elapsed * leakPerSec)
            local allowed = 0
            local retry = 0
            if level + 1 <= capacity then
                level = level + 1
                allowed = 1
            else
                -- 等水位降到能再容纳一个请求
                retry = math.ceil((level + 1 - capacity) / leakPerSec * 1000)
            end
            redis.call('HSET', KEYS[1], 'level', tostring(level), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], math.max(windowMs * 2, 60000))
            return allowed .. '|' .. math.floor(capacity - level) .. '|' .. retry
            """;

    /**
     * 只有值匹配持有者才删除，避免 A 的锁被 B 释放。
     *
     * <p>如果拆成客户端 GET + DEL 两步：A 判断值相等后、DEL 执行前，锁可能恰好过期并被 B 拿走，
     * 于是 A 删掉的是 B 的锁。放在一个脚本里就不存在这个间隙。</p>
     *
     * <p>KEYS[1]=锁 key，ARGV[1]=持有者唯一标识；返回 1=删除成功，0=锁已不属于自己。</p>
     */
    public static final String LOCK_RELEASE = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    /**
     * 只有值匹配持有者才续期。
     *
     * <p>KEYS[1]=锁 key，ARGV[1]=持有者标识，ARGV[2]=新的 TTL(ms)。
     * 返回 1 表示续期成功；0 表示锁已过期或被别人持有，此时业务应尽快停止并放弃写入。</p>
     *
     * <p>续期间隔必须小于 TTL（本仓库看门狗取 TTL/3），否则来不及续就过期了。</p>
     */
    public static final String LOCK_RENEW = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
            """;

    /**
     * 原子取出到期的延迟任务，避免多个实例重复消费。
     *
     * <p>KEYS[1]=延迟 ZSET，ARGV[1]=now(ms)；一次最多取 20 条，返回任务成员列表。</p>
     *
     * <p>当前 {@code DelayTaskQueue} 采用「先 range 再逐条 ZREM」的写法，以便记录执行历史；
     * 这个脚本是更严格的单次领取版本：查询与删除在同一个原子操作里完成，
     * 多实例同时轮询时只有一个实例能拿到同一批任务。</p>
     */
    public static final String DELAY_QUEUE_POP = """
            local items = redis.call('ZRANGEBYSCORE', KEYS[1], 0, ARGV[1], 'LIMIT', 0, 20)
            for _, item in ipairs(items) do
                redis.call('ZREM', KEYS[1], item)
            end
            return items
            """;
}
