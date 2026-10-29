package com.example.demo.jvm.gc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 {@code -Xlog:gc} 系列日志，把四种收集器的输出归一成同一张表。
 *
 * <p>为什么需要：GC 日志是「学 GC」唯一的一手材料，但 Serial / Parallel / G1 / ZGC
 * 写的行完全不同，读者对着四份日志看不出可比结论。这里只回答四个可比问题：
 * 触发了几次、STW 一共多久、最长一次多久、回收前后堆各是多少。</p>
 *
 * <p>实测（Corretto 21.0.13 + macOS x64）四种格式：</p>
 * <ul>
 *   <li>Serial / Parallel：
 *       {@code [1.392s][info][gc] GC(0) Pause Young (Allocation Failure) 53M->28M(193M) 49.639ms}</li>
 *   <li>G1：同上，另有 {@code GC(2) Concurrent Mark Cycle}、
 *       {@code GC(2) Pause Remark 102M->102M(200M) 1.538ms}、
 *       {@code GC(2) Concurrent Mark Cycle 11.253ms}（并发耗时，不带 Pause 字样）</li>
 *   <li>ZGC（非分代）主日志只有 {@code GC(0) Garbage Collection (Warmup) 24M(12%)->16M(8%)}，
 *       停顿要开 {@code -Xlog:gc*} 才出现在 {@code [gc,phases]} 里：
 *       {@code GC(0) Pause Mark Start 0.006ms}</li>
 *   <li>ZGC（分代）额外带周期标记：{@code GC(0) Y: Pause Mark Start (Major) 0.014ms}，
 *       整周期行用秒：{@code Major Collection (Warmup) 20M(10%)->20M(10%) 0.135s}</li>
 * </ul>
 *
 * <p>口径约定（必须写死，否则不同收集器无法比较）：
 * <ul>
 *   <li>STW 时长 = 行内含 {@code Pause} 且有尾时长度的行求和。
 *       因此 ZGC 会把一个周期里的 3 个停顿分别计入，Serial/G1 每个周期一行整体计入，两者都是「应用真正停下来的时间」；</li>
 *   <li>{@code Concurrent ...} 行不算 STW，单独计并发周期；</li>
 *   <li>整周期耗时（ZGC 分代的 {@code 0.135s}）不算 STW，只用于展示；</li>
 *   <li>一次「周期」按 {@code GC(n)} 的 n 去重。</li>
 * </ul></p>
 */
public final class GcLogParser {

    /**
     * 堆变化：{@code 53M->28M(193M)}（Serial/Parallel/G1）与
     * {@code 24M(12%)->16M(8%)}（ZGC，括号里是百分比、没有总量）都能匹配。
     */
    private static final Pattern HEAP =
            Pattern.compile("(\\d+)M(?:\\(\\d+%\\))?->(\\d+)M(?:\\((\\d+)M\\))?");
    /** 行尾时长：{@code 49.639ms} 或 {@code 0.135s}。 */
    private static final Pattern DURATION = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(ms|s)$");
    private static final Pattern GC_ID = Pattern.compile("GC\\((\\d+)\\)");

    private GcLogParser() {
    }

    /**
     * @param collector 由调用方标注（{@code -XX:+UseXXXGC} 的名字），日志本身不总带
     * @param log       完整 GC 日志文本
     */
    public record GcSummary(
            String collector,
            int cycles,
            int stwPauses,
            int concurrentCycles,
            int allocationStalls,
            double totalPauseMs,
            double maxPauseMs,
            int peakHeapBeforeMb,
            int lastHeapBeforeMb,
            int lastHeapAfterMb,
            Integer capacityMb,
            double reclaimedMb,
            List<String> longestPauses) {

        /**
         * 吞吐率 = 应用真正在跑的时间 / 总时间。
         *
         * @param wallClockMs 从进程启动到结束的挂钟时间（由实验类自己测）
         */
        public double throughputPercent(long wallClockMs) {
            if (wallClockMs <= 0) {
                return 0.0;
            }
            return Math.max(0, (wallClockMs - totalPauseMs) * 100.0 / wallClockMs);
        }
    }

    /**
     * @param log GC 日志全文（{@code -Xlog:gc*:file=...} 的内容）
     * @return 归一化统计；日志为空时返回全 0 的 summary，不抛异常
     */
    public static GcSummary parse(String collector, String log) {
        Set<String> cycleIds = new LinkedHashSet<>();
        List<Pause> pauses = new ArrayList<>();
        int concurrentCycles = 0;
        int allocationStalls = 0;
        int peakBefore = 0;
        int lastBefore = 0;
        int lastAfter = 0;
        Integer capacity = null;
        double reclaimed = 0;

        for (String raw : log.lines().toList()) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher id = GC_ID.matcher(line);
            if (id.find()) {
                // 一次 GC 周期会有多行（G1 的 Remark/Cleanup、ZGC 的多个 Pause 阶段），按编号去重才是周期数
                cycleIds.add(id.group(1));
            }
            if (line.contains("Allocation Stall")) {
                // 应用线程等不到内存被卡在分配点上：这是 ZGC 堆给小了最直接的证据
                allocationStalls++;
            }
            if (line.contains("Concurrent") && !line.contains("Pause") && DURATION.matcher(line).find()) {
                concurrentCycles++;
            }

            Matcher heap = HEAP.matcher(line);
            if (heap.find()) {
                int before = Integer.parseInt(heap.group(1));
                int after = Integer.parseInt(heap.group(2));
                peakBefore = Math.max(peakBefore, before);
                lastBefore = before;
                lastAfter = after;
                if (heap.group(3) != null) {
                    capacity = Integer.parseInt(heap.group(3));
                }
                if (after < before) {
                    // 只在真的净减少时累加：G1 的 Remark/Cleanup 行会出现 102M->102M，ZGC 甚至可能涨
                    reclaimed += before - after;
                }
            }

            if (line.contains("Pause")) {
                Matcher duration = DURATION.matcher(line);
                if (duration.find()) {
                    double millis = toMillis(Double.parseDouble(duration.group(1)), duration.group(2));
                    // 原始行留着，读者可以拿这里的最长停顿直接回日志里 grep 核对
                    pauses.add(new Pause(millis, line));
                }
            }
        }

        // 降序：最大的排在前面，一次取够 total / max / top3，不用维护两个并行列表
        pauses.sort((a, b) -> Double.compare(b.millis(), a.millis()));
        double total = pauses.stream().mapToDouble(Pause::millis).sum();
        double max = pauses.isEmpty() ? 0 : pauses.get(0).millis();
        List<String> longest = pauses.stream()
                .limit(3)
                .map(p -> format(p))
                .toList();
        return new GcSummary(collector, cycleIds.size(), pauses.size(), concurrentCycles, allocationStalls,
                round(total), round(max), peakBefore, lastBefore, lastAfter, capacity, round(reclaimed), longest);
    }

    /** 一次 STW 停顿：时长（毫秒）和它在日志里的原文。 */
    private record Pause(double millis, String line) {
    }

    /** 展示用：原文在前，方便对照；时长统一保留 3 位小数放在行尾。 */
    private static String format(Pause pause) {
        return pause.line() + "   [" + Math.round(pause.millis() * 1000.0) / 1000.0 + "ms]";
    }

    /** {@code 0.135s} 与 {@code 49.639ms} 统一到毫秒。 */
    private static double toMillis(double value, String unit) {
        return "s".equals(unit) ? value * 1000.0 : value;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
