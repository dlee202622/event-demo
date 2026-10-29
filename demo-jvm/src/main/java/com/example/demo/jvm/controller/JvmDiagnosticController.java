package com.example.demo.jvm.controller;

import com.example.demo.jvm.area.AreaSnapshot;
import com.example.demo.jvm.area.MemoryAreaService;
import com.example.demo.jvm.support.ClassHistogram;
import com.example.demo.jvm.support.DiagnosticCommands;
import com.example.demo.jvm.support.Units;
import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 诊断命令的 HTTP 出口，等价于在终端敲 {@code jcmd <pid> ...}。
 *
 * <p>为什么两件事都给：本模块的接口和 jcmd 输出同源（{@link DiagnosticCommands}
 * 走的是同一个 MBean），读者可以互相验证；但线上环境往往只有 shell，
 * 所以每个接口在 README 与教程里都写了等价的 jcmd 命令。</p>
 *
 * <p>耗时提示：{@code /histogram}（live=true 会先 Full GC）和 {@code /heap-dump}
 * 在几百 MB 堆上可能阻塞 1～10 秒；{@code /thread-dump}、{@code /native-memory}
 * 通常在几十毫秒内。演示进程的堆只有几百 MB，不会失控。</p>
 */
@RestController
@RequestMapping("/api/jvm")
public class JvmDiagnosticController {

    /** 堆转储文件名白名单：只允许字母数字和 - _，杜绝路径穿越。 */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_-]{1,40}");

    private final DiagnosticCommands diagnostics;
    private final MemoryAreaService areas;
    private final int histogramLimit;
    private final HotSpotDiagnosticMXBean hotSpot =
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);

    /**
     * @param diagnostics    诊断命令通道，{@code -all} 与否都由它决定用哪条 jcmd 命令
     * @param areas          同一个快照服务：直方图、NMT、GC 三个视图共享一份数据源，
     *                       避免出现「两个接口给出的堆用量对不上」
     * @param histogramLimit 榜单默认长度，来自 {@code demo.jvm.histogram-limit}（默认 12）
     *                       ——12 是「一屏能看完 + 足够盖住 [B/String/Method 这几常客」的折中，
     *                       请求方可以用 {@code ?limit=} 覆盖，实现里会夹到 1..200
     */
    public JvmDiagnosticController(DiagnosticCommands diagnostics,
                                   MemoryAreaService areas,
                                   @Value("${demo.jvm.histogram-limit:12}") int histogramLimit) {
        this.diagnostics = diagnostics;
        this.areas = areas;
        this.histogramLimit = histogramLimit;
    }

    /** 分代明细：各代 used/committed、G1 region 大小、Metaspace 与压缩类空间。 */
    @GetMapping(value = "/heap-info", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> heapInfo() {
        return text(diagnostics.heapInfo());
    }

    /**
     * 类直方图：哪个类的实例最多、占字节最多。
     *
     * @param live  true（默认）只统计存活对象，会先触发一次 Full GC——排查泄漏必须用它；
     *              false 包含垃圾，用来说明「实例多不等于泄漏」
     * @param limit 返回前 N 条，默认取 {@code demo.jvm.histogram-limit}（12）
     */
    @GetMapping("/histogram")
    public HistogramResult histogram(@RequestParam(defaultValue = "true") boolean live,
                                     @RequestParam(required = false) Integer limit) {
        int size = clampLimit(limit);
        String raw = diagnostics.classHistogram(live);
        return new HistogramResult(live, Units.mb(ClassHistogram.totalBytes(raw)),
                ClassHistogram.parse(raw, size));
    }

    /** NMT 汇总原文；进程没带 {@code -XX:NativeMemoryTracking=summary} 时返回 400 并给出补救参数。 */
    @GetMapping(value = "/native-memory", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> nativeMemory(@RequestParam(defaultValue = "summary") String level) {
        return text(diagnostics.nativeMemory(level));
    }

    /** 全量 VM 参数（含默认值），用来回答「我没设过这个参数，它现在到底是多少」。 */
    @GetMapping(value = "/flags", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> flags() {
        return text(diagnostics.allFlags());
    }

    /** 元空间明细：chunk 分布与占用来源，解释「Metaspace 涨了是谁干的」。 */
    @GetMapping(value = "/metaspace", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> metaspace() {
        return text(diagnostics.metaspaceInfo());
    }

    /** 类加载器统计，配合 Metaspace OOM 实验看 loader 数量。 */
    @GetMapping(value = "/classloaders", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> classLoaders() {
        return text(diagnostics.classLoaderStats());
    }

    /** 全线程栈快照。 */
    @GetMapping(value = "/thread-dump", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> threadDump() {
        return text(diagnostics.threadDump());
    }

    /** 死锁检测，MXBean 直接给出结果，不需要人工读栈。 */
    @GetMapping(value = "/deadlocks", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> deadlocks() {
        return text(diagnostics.deadlocks());
    }

    /**
     * 主动触发一次 Full GC，并给出前后对比。
     *
     * <p>教学点：GC 统计是累计值，只有「前一次读 / GC / 后一次读」三段放一起，
     * 才能看见 collectionCount 增加了几个、堆下降了多少 MB。</p>
     */
    @PostMapping("/gc")
    public GcRunResult runGc() {
        Map<String, Long> before = collectorCounts();
        double heapBefore = Units.mb(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        // MBean 版的 GC.run 不返回文本（jcmd 版会附带一句回收后的堆摘要），所以只取前后差值
        diagnostics.gcRun();
        Map<String, Long> after = collectorCounts();
        double heapAfter = Units.mb(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        Map<String, Long> delta = new LinkedHashMap<>();
        before.forEach((name, count) -> delta.put(name, after.getOrDefault(name, count) - count));
        return new GcRunResult(heapBefore, heapAfter,
                // 释放量可能是负数：Full GC 期间对象仍在分配，如实返回而不是取绝对值
                Math.round((heapBefore - heapAfter) * 100.0) / 100.0,
                delta);
    }

    /**
     * 导出堆转储，等价于 {@code jcmd <pid> GC.heap_dump}。
     *
     * @param name 文件名（只允许字母数字与 - _），实际写入 {@code <tmpdir>/demo-jvm/<name>-<pid>.hprof}
     */
    @PostMapping("/heap-dump")
    public HeapDumpResult heapDump(@RequestParam(defaultValue = "heap") String name) {
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("name 只允许字母、数字、- 和 _，收到：" + name);
        }
        try {
            Path directory = Path.of(System.getProperty("java.io.tmpdir"), "demo-jvm");
            Files.createDirectories(directory);
            Path file = directory.resolve(name + "-" + ProcessHandle.current().pid() + ".hprof");
            // live=true：先做一次 Full GC 再导出，文件里只剩存活对象，才是排查泄漏该看的样子
            hotSpot.dumpHeap(file.toString(), true);
            return new HeapDumpResult(file.toString(), Units.mb(Files.size(file)), true);
        } catch (IOException e) {
            throw new IllegalStateException("写堆转储失败：" + e.getMessage(), e);
        }
    }

    private Map<String, Long> collectorCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (AreaSnapshot.Collector collector : areas.collectorViews()) {
            counts.put(collector.name(), collector.collectionCount());
        }
        return counts;
    }

    private int clampLimit(Integer limit) {
        if (limit == null) {
            return histogramLimit;
        }
        // 上限 200：再多就不再是「给人看的榜单」，而是把整份直方图搬进响应
        return Math.max(1, Math.min(200, limit));
    }

    private static ResponseEntity<String> text(String body) {
        return ResponseEntity.ok().contentType(MediaType.valueOf("text/plain;charset=UTF-8")).body(body);
    }

    /**
     * @param totalMb   全部类的实例字节合计，已换算成 MB。
     *                  字段名刻意带单位：早名叫 totalBytes 却返回 MB，
     *                  读者按名字理解会得出「只有 77 字节」的荒谬结论
     * @param entries   排名前 N 的类，其中 {@code bytes} 仍是原始字节数
     */
    public record HistogramResult(boolean live, double totalMb, List<ClassHistogram.Entry> entries) {
    }

    /** @param collectorCountDelta 每个收集器本次增加的回收次数，0 说明这次 GC 没被它执行 */
    public record GcRunResult(double heapUsedBeforeMb, double heapUsedAfterMb, double freedMb,
                              Map<String, Long> collectorCountDelta) {
    }

    public record HeapDumpResult(String file, double sizeMb, boolean live) {
    }
}
