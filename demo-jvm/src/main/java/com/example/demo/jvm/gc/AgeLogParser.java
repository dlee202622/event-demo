package com.example.demo.jvm.gc;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 {@code -Xlog:gc*,gc+age=trace} 的日志读成几张可以直接比较的数字。
 *
 * <p>为什么单独一个类而不是在 {@link GcLogParser} 里加几行：两者读的虽然是同一份文件，
 * 但问的问题完全不同。{@code GcLogParser} 问「停了多少次、多久」，
 * 本类问「对象多大、活过几轮、什么时候被搬进老年代」。
 * 混在一起会让两边的正则互相干扰（年龄表里也有字节数，容易被堆行正则吃掉）。</p>
 *
 * <p>每一条正则都对照本机 JDK 21 的真实日志写的，样例（Serial，64 MB 堆）：
 * <pre>
 * [0.144s][debug][gc,age] GC(0) Desired survivor size 1114112 bytes, new threshold 1 (max threshold 15)
 * [0.144s][trace][gc,age] GC(0) Age table with threshold 1 (max threshold 15)
 * [0.144s][trace][gc,age] GC(0) - age   1:    2228224 bytes,    2228224 total
 * [0.144s][info ][gc,heap] GC(0) DefNew: 17464K(19648K)-&gt;2176K(19648K) ...
 * [0.144s][info ][gc,heap] GC(0) Tenured: 1056K(43712K)-&gt;14473K(43712K)
 * </pre>
 * G1 的对应行是 {@code Old regions: 1->3}、{@code Humongous regions: 13->13}，
 * 停顿行里额外带 cause：{@code Pause Young (Concurrent Start) (G1 Humongous Allocation)}。</p>
 *
 * <p>坑（实测，都会影响读日志的结论）：
 * <ul>
 *   <li>年龄表是 {@code trace} 级、{@code Desired survivor size} 是 {@code debug} 级，
 *       只开 {@code -Xlog:gc*} 一个都看不到，必须写 {@code gc+age=trace}；</li>
 *   <li>ZGC（非分代）压根没有年龄表——它不搬移「按年龄晋升」的对象，
 *       所以 {@code cyclesWithAge=0} 是正常结论，不是解析失败；</li>
 *   <li>G1 的老年代增量只能按 region 数换算，region 大小要自己从
 *       {@code [gc,init] Heap Region Size: 2M} 这行读出来，不同堆大小会自适应；</li>
 *   <li>Serial/Parallel 的老年代行名不一样（{@code Tenured:} vs {@code ParOldGen:}），
 *       所以两个名字都要匹配，只写一个会得到 Parallel 全 0。</li>
 * </ul></p>
 */
public final class AgeLogParser {

    /** {@code Desired survivor size N bytes, new threshold T (max threshold M)}。 */
    private static final Pattern DESIRED =
            Pattern.compile("Desired survivor size (\\d+) bytes, new threshold (\\d+) \\(max threshold (\\d+)\\)");

    /** {@code - age   1:    2228224 bytes,    2228224 total}，年龄表的一行。 */
    private static final Pattern AGE_ROW =
            Pattern.compile("-\\s+age\\s+(\\d+):\\s+(\\d+) bytes,\\s+(\\d+) total");

    /** Serial 的 {@code Tenured:} 与 Parallel 的 {@code ParOldGen:}：格式一样，只是名字不同。 */
    private static final Pattern OLD_KB =
            Pattern.compile("(?:Tenured|ParOldGen): (\\d+)K\\(\\d+K\\)->(\\d+)K\\(\\d+K\\)");

    /** G1 用 region 记账：{@code Old regions: 1->3}。 */
    private static final Pattern OLD_REGIONS = Pattern.compile("Old regions: (\\d+)->(\\d+)");

    /** {@code [gc,init] Heap Region Size: 2M}，把 G1 的 region 数换算成字节要用。 */
    private static final Pattern REGION_SIZE = Pattern.compile("Heap Region Size: (\\d+)([KM])");

    /** 行首的周期编号，用来把同一周期里重复出现的 cause 行去重。 */
    private static final Pattern GC_ID = Pattern.compile("GC\\((\\d+)\\)");

    /** 一次周期解析后的汇总，字段全部是「日志里读到」而不是「推测」。 */
    public record AgeSummary(String label,
                             int cycles,
                             int cyclesWithAge,
                             int youngGcs,
                             long desiredSurvivorKb,
                             int maxTenuringThreshold,
                             String thresholdsSeen,
                             int thresholdOneCycles,
                             int maxAgeSeen,
                             long survivorPeakKb,
                             long promotedMb,
                             long oldGenEndMb,
                             int humongousTriggers,
                             int evacuationFailures,
                             int fullGcs) {

        /** 动态年龄是否被压到 1：这是本场景最想给读者看的一列。 */
        boolean tenuredEarly() {
            return thresholdOneCycles > 0;
        }
    }

    /**
     * 解析一份 GC 日志。
     *
     * @param label 变体名，原样带回报告，方便表格输出
     * @param log   日志全文；空串（子进程没起来）时返回全 0 的汇总，不抛异常
     * @return 汇总；解析不到的字段保持 0 / 空，绝不猜测
     */
    public static AgeSummary parse(String label, String log) {
        int cycles = 0;
        int cyclesWithAge = 0;
        long desiredKb = 0;
        int maxThreshold = 0;
        Set<Integer> thresholds = new LinkedHashSet<>();
        int thresholdOne = 0;
        int maxAge = 0;
        long survivorPeakKb = 0;
        long promotedMb = 0;
        long oldEndKb = 0;
        // 这三个按「周期编号」去重计数，因为同一件事会在 [gc,start] 和 [gc] 两行里各写一次
        Set<String> humongousIds = new LinkedHashSet<>();
        Set<String> evacFailIds = new LinkedHashSet<>();
        Set<String> fullGcIds = new LinkedHashSet<>();
        // Young GC 总数单独数一份：Pretenure 组一条 gc,age 日志都不会有，
        // 如果只看 cycles=0，读者会以为「解析器没读到」而不是「确实没有 Survivor 拷贝」
        Set<String> youngGcIds = new LinkedHashSet<>();
        long regionKb = 0;

        // 逐行扫而不是整篇正则：一份日志里年龄表、堆行、停顿行交错出现，
        // 分行处理才能保证 "- age 1: ... total" 不会被 OLD_KB 的 "->" 误吃
        String[] lines = log.split("\\R");
        for (String line : lines) {
            Matcher region = REGION_SIZE.matcher(line);
            if (region.find()) {
                // 只有 M / K 两种单位出现过；1M = 1024K
                regionKb = Long.parseLong(region.group(1)) * ("M".equals(region.group(2)) ? 1024 : 1);
                continue;
            }
            Matcher desired = DESIRED.matcher(line);
            if (desired.find()) {
                cycles++;
                // 同一个周期里 Desired 只出现一次，所以这里覆盖取值就是「最后一次的上限」；
                // 取第一个值更符合「参数没动过」的直觉，两者实测相同
                desiredKb = Long.parseLong(desired.group(1)) / 1024;
                int threshold = Integer.parseInt(desired.group(2));
                maxThreshold = Integer.parseInt(desired.group(3));
                thresholds.add(threshold);
                if (threshold == 1) {
                    thresholdOne++;
                }
                continue;
            }
            Matcher ageRow = AGE_ROW.matcher(line);
            if (ageRow.find()) {
                cyclesWithAge++;
                maxAge = Math.max(maxAge, Integer.parseInt(ageRow.group(1)));
                // total 这一列就是这一次 Young GC 之后 Survivor 里还剩多少，
                // 用它而不是用 group(2)：多个年龄段时 total 才是整个 from-space
                survivorPeakKb = Math.max(survivorPeakKb, Long.parseLong(ageRow.group(3)) / 1024);
                continue;
            }
            Matcher old = OLD_KB.matcher(line);
            if (old.find()) {
                long before = Long.parseLong(old.group(1));
                long after = Long.parseLong(old.group(2));
                // 只累加正增量：Full GC 会让老年代「变小」，那不该记进「晋升了多少」
                if (after > before) {
                    promotedMb += (after - before) / 1024;
                }
                oldEndKb = Math.max(oldEndKb, after);
                continue;
            }
            Matcher oldRegions = OLD_REGIONS.matcher(line);
            if (oldRegions.find()) {
                long before = Long.parseLong(oldRegions.group(1));
                long after = Long.parseLong(oldRegions.group(2));
                // region 大小读不到时（比如日志缺 gc,init）就记 0，
                // 宁缺勿假：给一个猜出来的换算比空着更容易误导读者
                if (after > before && regionKb > 0) {
                    promotedMb += (after - before) * regionKb / 1024;
                }
                if (regionKb > 0) {
                    oldEndKb = Math.max(oldEndKb, after * regionKb);
                }
                continue;
            }
            // cause 关键字在 [gc,start] 和 [gc] 两行里都会出现（同一周期打两遍），
            // 所以按 GC(n) 编号放进 Set 去重，而不是累加行数——实测行数正好是周期的两倍
            if (line.contains("Pause Young") && line.contains("G1 Humongous Allocation")) {
                addGcId(humongousIds, line);
            }
            if (line.contains("(Evacuation Failure)")) {
                addGcId(evacFailIds, line);
            }
            if (line.contains("Pause Young")) {
                addGcId(youngGcIds, line);
            }
            if (line.contains("Pause Full")) {
                addGcId(fullGcIds, line);
            }
        }
        return new AgeSummary(label, cycles, cyclesWithAge, youngGcIds.size(), desiredKb, maxThreshold,
                thresholds.toString(), thresholdOne, maxAge, survivorPeakKb,
                promotedMb, oldEndKb / 1024, humongousIds.size(), evacFailIds.size(), fullGcIds.size());
    }

    /** 从形如 {@code [0.191s][info][gc] GC(4) ...} 的行里取出 4，放进集合去重。 */
    private static void addGcId(Set<String> ids, String line) {
        Matcher matcher = GC_ID.matcher(line);
        if (matcher.find()) {
            ids.add(matcher.group(1));
        }
    }

    private AgeLogParser() {
    }
}
