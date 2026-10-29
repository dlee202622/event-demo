package com.example.demo.jvm.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// Boot 4 把这个注解从 boot.test.autoconfigure.web.servlet 挪到了 boot.webmvc.test.autoconfigure：
// 写旧路径不会编译失败得很难看，而是「找不到注解」，所以在这里钉一行说明。
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 场景 1 的接口测试：观测面本身也是产品，它坏在「报错」之外更常见的是「报得不对」。
 *
 * <p>为什么值得为只读接口写测试：这些接口的每一个字段都会变成教程里的一句话。
 * {@code /api/jvm/histogram} 曾经稳定 500——因为把 {@code -live} 传给了
 * {@code GC.class_histogram}（这个命令压根没有该选项）。这类错误不会让业务挂掉，
 * 只会让教程里出现一句错误结论，所以只有接口测试能拦住它。</p>
 *
 * <p>为什么用 MockMvc 而不是 TestRestTemplate：Boot 4 把测试客户端拆成了两套
 * （{@code spring-boot-resttestclient} / {@code spring-boot-restclient}），
 * 想在 {@code @SpringBootTest} 里拿到 {@code TestRestTemplate} 得额外引一个模块；
 * 而本模块的接口没有一件需要真实 TCP——要验的是「路由 + 序列化出来的字段」，
 * MockMvc 直接读 DispatcherServlet 吐出的 JSON 字节就够了。
 * 少一个依赖，也<strong>不会占用任何端口</strong>：用户在 IDEA 里常驻着 9001~9005 的实例，
 * 测试起随机端口就有撞车的风险，MOCK 环境从根上排除了它。</p>
 *
 * <p>断言全部写在解析后的 {@link JsonNode} 上，不是写死的字符串片段：
 * 字段名（{@code heldBlocks}、{@code threadCount}）就是接口契约，改名要在这里红一次，
 * 而不是等到读者照着 README 的 curl 输出抄的时候才发现。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class JvmObservabilityApiTest {

    @Autowired
    private MockMvc mockMvc;

    /** 由 Spring 注入的是应用里那台序列化器，测试读回来的正是它写出去的字节，序列化行为也一并被测到。 */
    @Autowired
    private ObjectMapper mapper;

    @Test
    @DisplayName("/areas 一次给全：分区、池、收集器、线程、直接内存、NMT、参数")
    void areasSnapshotHasEverySection() throws Exception {
        JsonNode body = ok(get("/api/jvm/areas"));

        // 教材分区：程序计数器 / 虚拟机栈 / 本地方法栈 / 堆 / 方法区（+ 运行时常量池等）
        assertTrue(body.get("areas").size() >= 5, "areas=" + body.get("areas").size());
        // 每个区都必须写清「怎么量」，这是本模块的立身之本：量不出来的区也要说明为什么量不出来
        body.get("areas").forEach(area ->
                assertTrue(!area.get("howToMeasure").asString().isBlank(), String.valueOf(area)));

        assertTrue(hasText(body.get("pools"), "Metaspace"),
                "池里必须出现 Metaspace，它是方法区在 HotSpot 里的落点");

        JsonNode process = body.get("process");
        assertTrue(process.get("pid").asLong() > 0, "pid 要能拿去喂 jcmd");
        assertNotNull(process.get("gcAlgorithm"));

        assertTrue(body.get("threads").get("threadCount").asInt() > 0,
                String.valueOf(body.get("threads")));
    }

    @Test
    @DisplayName("/histogram?live=true 只统计存活对象——这里是曾经 500 的那条回归")
    void histogramLiveOptionWorks() throws Exception {
        // GC.class_histogram 不支持 -live，实现里改成「先 GC.run 再取默认（可达）视图」。
        // 这条断言就是那次的教训钉成的钉子：接口一旦又回到传 -live，立刻 500 变红。
        JsonNode live = ok(get("/api/jvm/histogram?live=true"));
        assertTrue(live.get("live").asBoolean());
        assertTrue(live.get("totalMb").asDouble() > 0, String.valueOf(live.get("totalMb")));
        assertEquals(12, live.get("entries").size(), "默认取 demo.jvm.histogram-limit=12");

        // live=false 连垃圾一起算，总量不可能比存活视图小（本机实测 81.62 MB vs 77.73 MB）。
        // 只断言 >=：测试 JVM 里刚好没垃圾时两者会相等，断言严格大于就成了假阴性。
        JsonNode all = ok(get("/api/jvm/histogram?live=false"));
        assertTrue(all.get("totalMb").asDouble() >= live.get("totalMb").asDouble(),
                "live=false " + all.get("totalMb") + " 应不小于 live=true " + live.get("totalMb"));
    }

    @Test
    @DisplayName("/histogram 的 limit 被夹在 1..200：再多就不是给人看的榜单了")
    void histogramLimitIsClamped() throws Exception {
        assertEquals(1, ok(get("/api/jvm/histogram?limit=0")).get("entries").size());
        assertEquals(3, ok(get("/api/jvm/histogram?limit=3")).get("entries").size());
        // 200 是硬上限：直方图本身要遍历整个堆，请求方写 999999 也不该让观测面自己去冒这个险
        assertEquals(200, ok(get("/api/jvm/histogram?limit=999999")).get("entries").size());
    }

    @Test
    @DisplayName("/native-memory 在开 NMT 的进程里返回原文；没开时是 400 加补救参数")
    void nativeMemoryEndpoint() throws Exception {
        // surefire 的 argLine 已经带 -XX:NativeMemoryTracking=summary，所以这里走正例。
        // 换台机器没开 NMT 时这条会失败，失败信息里的 400 响应体正是我们要的提示文案。
        MvcResult result = mockMvc.perform(get("/api/jvm/native-memory")).andReturn();
        assertEquals(HttpStatus.OK.value(), result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertTrue(result.getResponse().getContentAsString().contains("Total"),
                "NMT 汇总必须有 Total 段：" + result.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("/allocate 的上限与非法值都回 400，而不是把观测进程打死")
    void allocateGuards() throws Exception {
        // 上限值本身要出现在消息里，否则读者只知道失败不知道调哪个参数
        MvcResult tooBig = mockMvc.perform(post("/api/jvm/allocate?mb=100000")).andReturn();
        assertEquals(HttpStatus.BAD_REQUEST.value(), tooBig.getResponse().getStatus(),
                tooBig.getResponse().getContentAsString());
        assertTrue(tooBig.getResponse().getContentAsString().contains("64"),
                tooBig.getResponse().getContentAsString());

        MvcResult negative = mockMvc.perform(post("/api/jvm/allocate?mb=-1")).andReturn();
        assertEquals(HttpStatus.BAD_REQUEST.value(), negative.getResponse().getStatus(),
                negative.getResponse().getContentAsString());
    }

    @Test
    @DisplayName("保留引用 → 分配 → 查看 → 清空：整条链路的状态自洽")
    void allocateAndClearRoundTrip() throws Exception {
        post200("/api/jvm/allocate/clear");
        assertEquals(0, ok(get("/api/jvm/hold")).get("heldBlocks").asInt());

        JsonNode allocated = post200("/api/jvm/allocate?mb=4&retain=true");
        assertEquals(4, allocated.get("megabytes").asInt());
        assertEquals(1, allocated.get("heldBlocks").asInt());

        JsonNode heldAfter = ok(get("/api/jvm/hold"));
        assertEquals(1, heldAfter.get("heldBlocks").asInt());
        // 保留块数变了但 JVM 总用量不一定同步变：上一轮的垃圾可能还没被回收，
        // 所以这里只断言 heldMb，不去断言 heapUsedMb 的增减
        assertTrue(heldAfter.get("heldMb").asDouble() >= 4.0, String.valueOf(heldAfter));

        // 同一个字段名在两个接口上是两个意思，这是最容易写错的一条断言：
        // /hold 的 heldBlocks 是「现在还留着几块」，/allocate/clear 的是「刚放开几块」。
        // clear 故意不把返回值改成 0——它要告诉读者这次到底放开了多少，
        // 而「放开不等于回收」正是这个接口想教的东西。
        JsonNode cleared = post200("/api/jvm/allocate/clear");
        assertEquals(1, cleared.get("heldBlocks").asInt(), String.valueOf(cleared));
        assertEquals(4.0, cleared.get("heldMb").asDouble(), String.valueOf(cleared));
        // 清完再问 /hold，才是真的归零
        assertEquals(0, ok(get("/api/jvm/hold")).get("heldBlocks").asInt());
        // heapUsedMb 不跟着归零：引用断了，垃圾还在堆里等下一次 GC
        assertTrue(cleared.get("heapUsedMb").asDouble() >= 4.0, String.valueOf(cleared));
    }

    @Test
    @DisplayName("/gc 的差值观察：collectionCount 是累计值，接口给的是「现在多少」")
    void collectorCountersAreCumulative() throws Exception {
        JsonNode collectors = ok(get("/api/jvm/gc"));
        assertTrue(!collectors.isEmpty(), "至少一个收集器 bean");
        // 当前收集器只有一个（Serial/G1 之一），bean 名字必须和 /areas 的 gcAlgorithm 同源
        assertTrue(!collectors.get(0).get("name").asString().isEmpty());
        assertTrue(collectors.get(0).has("collectionCount"),
                "累计次数是看 GC 有没有干活的唯一入口");
        assertTrue(collectors.get(0).has("memoryPools"),
                "memoryPools 说明这个收集器管哪几个池，差值观察要用它对上 /pools 的名字");
    }

    /** 发一次 GET，把响应体交给应用自己的 Jackson 3 解析；状态码必须是 200，否则直接把正文塞进失败信息。 */
    private JsonNode ok(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(HttpStatus.OK.value(), result.getResponse().getStatus(), body);
        return mapper.readTree(body);
    }

    /** POST 只读接口的写操作（分配 / 清空），同样要求 200 并返回解析后的正文。 */
    private JsonNode post200(String path) throws Exception {
        MvcResult result = mockMvc.perform(post(path).accept(MediaType.APPLICATION_JSON)).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(HttpStatus.OK.value(), result.getResponse().getStatus(), body);
        return mapper.readTree(body);
    }

    /** 数组里是否存在某个 {@code name} 等于给定值的对象；避免为了一句断言写三层循环。 */
    private static boolean hasText(JsonNode array, String name) {
        for (JsonNode node : array) {
            if (node.has("name") && name.equals(node.get("name").asString())) {
                return true;
            }
        }
        return false;
    }
}
