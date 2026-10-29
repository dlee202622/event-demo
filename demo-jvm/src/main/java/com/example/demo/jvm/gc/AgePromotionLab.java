package com.example.demo.jvm.gc;

import com.example.demo.jvm.lab.ChildJvm;
import com.example.demo.jvm.lab.LabArgs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 场景5：对象到底活了几轮、什么时候被搬进老年代、多大算「大对象」。
 *
 * <p>为什么值得单独做一个场景：「对象年龄到 15 才晋升」是背下来的，
 * 但真实运行里 {@code MaxTenuringThreshold} 几乎从不起作用——HotSpot 会在每次 Young GC 时
 * 按 Survivor 的容纳能力<em>动态</em>压低阈值（日志里的 {@code new threshold 1}）。
 * 只有把 {@code -Xlog:gc*,gc+age=trace} 打开，才能看到「期望的年龄」和「实际的年龄」这两套规则在打架。
 * 本场景的每张表都来自这份日志，不来自推测。</p>
 *
 * <p>六个变体分三组，每组都只有一个变量：
 * <ul>
 *   <li>{@code serial-default} vs {@code serial-bigSurvivor} vs {@code serial-maxTenure1}：
 *       同一个负载，只动 Survivor 容量和 {@code MaxTenuringThreshold}，看动态阈值怎么变；</li>
 *   <li>{@code serial-1m-default} vs {@code serial-1m-pretenure}：
 *       1 MB 的对象，只加 {@code -XX:PretenureSizeThreshold}，看「直接进老年代」省下的搬运
 *       换来的是 Full GC；</li>
 *   <li>{@code g1-1m-humongous} vs {@code g1-32k-normal}：同一个 G1、同一个 region 大小，
 *       只把对象从 1 MB 换成 32 KB，看 {@code (G1 Humongous Allocation)} 这个 GC 触发原因消失。</li>
 * </ul></p>
 *
 * <p>坑：
 * <ul>
 *   <li>{@code PretenureSizeThreshold} 只对 Serial / Parallel 生效，G1 走的是 humongous 那套规则，
 *       在 G1 上配它属于「参数写了但没人理」；</li>
 *   <li>年龄表是 trace 级日志，只开 {@code gc*}（info）看不到，必须显式 {@code gc+age=trace}；</li>
 *   <li>负载里对象大小要跟着变，否则「大对象」那两组根本没有大对象可分配。</li>
 * </ul></p>
 *
 * <p>子进程复用 {@link GcLab}：它已经会把校验和、挂钟时间写进报告，
 * 本类只负责加参数、加日志文件、再解析日志。</p>
 */
public final class AgePromotionLab {

    /**
     * 一个变体。
     *
     * @param label    表格里显示的名字
     * @param flags    本变体独有的 JVM 参数（堆大小与日志由本类统一加，保证可比）
     * @param objectKb 负载里单个对象的大小，KB——这是「大对象」组的唯一变量
     * @param note     这一行想说明什么，直接打进输出，省得读者回去翻源码
     */
    private record Variant(String label, List<String> flags, int objectKb, String note) {
    }

    /** 一个变体跑完的结果。 */
    private record Row(Variant variant, AgeLogParser.AgeSummary summary, Map<String, String> report) {

        String checksum() {
            return report.getOrDefault("workload.checksum", "-");
        }
    }

    /**
     * 场景 5 的入口：七个变体各起一个子进程，每个都留一份 {@code gc,age} 日志，最后汇总成一张表。
     *
     * <p>用法：{@code java -cp demo-jvm/target/classes com.example.demo.jvm.gc.AgePromotionLab
     * --heapMb=64 --rounds=40 --churnMb=16 --liveMb=8 --detail=true}。
     * 变体分三组：前三行是「动态阈值」（默认 / SurvivorRatio=2 / MaxTenuringThreshold=1），
     * 中间两行是「大对象怎么进老年代」（1 MB 普通分配 vs PretenureSizeThreshold），
     * 后两行是 G1 的 humongous 与其对照组。三组共用同一份负载参数，
     * 表格里晋升量的差别才只可能来自分配方式本身。</p>
     */
    public static void main(String[] args) {
        LabArgs parsed = LabArgs.parse(args);
        // 64 MB：故意给小，好让 Survivor 一挤就满、动态阈值立刻掉到 1。
        // 堆给到 4 GB 时这些现象会全部消失，实验就变成「一切正常」，什么都看不出来
        int heapMb = parsed.number("heapMb", 64);
        int rounds = parsed.number("rounds", 40);
        int churnMb = parsed.number("churnMb", 16);
        int liveMb = parsed.number("liveMb", 8);
        boolean detail = parsed.bool("detail", false);

        Path logDir = labDir();
        List<Row> rows = new ArrayList<>();
        for (Variant variant : variants()) {
            rows.add(runVariant(variant, logDir, heapMb, rounds, churnMb, liveMb));
        }

        printTable(rows, heapMb, rounds, churnMb, liveMb);
        if (detail) {
            // 表格之外的东西必须能追溯到日志原文，否则「实测」只是口号
            System.out.println();
            System.out.println("每个变体的年龄表原文（节选自各自的 gc.log）：");
            for (Row row : rows) {
                System.out.println();
                System.out.println("## " + row.variant().label());
                printAgeLines(logDir, row.variant().label());
            }
        }
    }

    /** 变体清单；顺序即表格顺序，三组对照一眼能对比。 */
    private static List<Variant> variants() {
        return List.of(
                new Variant("serial-default", List.of("-XX:+UseSerialGC"), 32,
                        "默认参数：动态阈值被 Survivor 挤到 1，对象只熬一轮就晋升"),
                new Variant("serial-bigSurvivor", List.of("-XX:+UseSerialGC", "-XX:SurvivorRatio=2"), 32,
                        "Survivor 放大（Eden:Survivor 从 8:1 变 2:1）：装得下了，阈值才可能涨上去"),
                new Variant("serial-maxTenure1", List.of("-XX:+UseSerialGC", "-XX:MaxTenuringThreshold=1"), 32,
                        "把上限写死成 1：和 serial-default 几乎一样，说明默认时本来就是 1 在起作用"),
                new Variant("serial-1m-default", List.of("-XX:+UseSerialGC"), 1024,
                        "1 MB 对象、不设 Pretenure：先在 Eden 里待一轮，靠复制搬进老年代"),
                new Variant("serial-1m-pretenure", List.of("-XX:+UseSerialGC", "-XX:PretenureSizeThreshold=1m"), 1024,
                        "1 MB 对象 + PretenureSizeThreshold：分配即老年代，代价是 Full GC 变多"),
                new Variant("g1-1m-humongous", List.of("-XX:+UseG1GC", "-XX:G1HeapRegionSize=2m"), 1024,
                        "G1 + 2 MB region：≥1 MB 的对象就是 humongous，会自己触发 Young GC"),
                new Variant("g1-32k-normal", List.of("-XX:+UseG1GC", "-XX:G1HeapRegionSize=2m"), 32,
                        "同一套 G1 参数、对象换成 32 KB：humongous 触发次数归零，对照组"));
    }

    private static Row runVariant(Variant variant, Path logDir, int heapMb, int rounds, int churnMb, int liveMb) {
        Path logFile = logDir.resolve("age-" + variant.label() + "-" + System.nanoTime() + ".log");
        List<String> flags = new ArrayList<>();
        flags.add("-Xms" + heapMb + "m");
        flags.add("-Xmx" + heapMb + "m");
        flags.addAll(variant.flags());
        // 引号外不能有冒号以外的分隔符写错：gc+age=trace 是「标签+级别」，:file= 才是「输出目标」
        flags.add("-Xlog:gc*,gc+age=trace:file=" + logFile);

        List<String> labArgs = List.of(
                "--collector=" + variant.label(),
                "--rounds=" + rounds,
                "--churnMb=" + churnMb,
                "--liveMb=" + liveMb,
                "--objectKb=" + variant.objectKb());
        // 64 MB 堆跑 40 轮很快，但 Pretenure 组会连着 Full GC，留出 90 秒余量
        ChildJvm.Result result = ChildJvm.run(flags, GcLab.class.getName(), labArgs, 90_000);
        AgeLogParser.AgeSummary summary = AgeLogParser.parse(variant.label(), readLog(logFile));
        return new Row(variant, summary, result.report());
    }

    private static void printTable(List<Row> rows, int heapMb, int rounds, int churnMb, int liveMb) {
        System.out.println("# lab=age-promotion  heap=-Xms" + heapMb + "m -Xmx" + heapMb
                + "m  workload=rounds:" + rounds + " churn:" + churnMb + "MB live:" + liveMb + "MB");
        System.out.println();
        System.out.printf("%-20s %7s %6s %6s %12s %11s %6s %10s %9s %9s %7s %7s %7s%n",
                "variant", "youngGC", "cycles", "ages", "desurvKB", "thresholds", "maxAge",
                "survPeakKB", "promotedMB", "oldEndMB", "humong", "evacF", "fullGC");
        for (Row row : rows) {
            AgeLogParser.AgeSummary s = row.summary();
            // 校验和只在「同一个 objectKb 的组」内部比较：
            // 对象大小本身就是本场景的自变量，32 KB 组和 1 MB 组的校验和当然不同
            String expected = rows.stream()
                    .filter(other -> other.variant().objectKb() == row.variant().objectKb())
                    .map(Row::checksum)
                    .findFirst()
                    .orElse("-");
            System.out.printf("%-20s %7d %6d %6d %12d %11s %6d %10d %9d %9d %7d %7d %7d%s%n",
                    s.label(), s.youngGcs(), s.cycles(), s.cyclesWithAge(), s.desiredSurvivorKb(),
                    s.thresholdsSeen(), s.maxAgeSeen(), s.survivorPeakKb(),
                    s.promotedMb(), s.oldGenEndMb(), s.humongousTriggers(),
                    s.evacuationFailures(), s.fullGcs(),
                    expected.equals(row.checksum()) ? "" : "  <!=同组工作量不一致");
        }
        System.out.println();
        System.out.println("字段说明");
        System.out.println("  youngGC      'Pause Young' 的周期数（按 GC(n) 去重）；它用来区分「真没 Survivor 拷贝」和「解析失败」");
        System.out.println("  cycles       带 'Desired survivor size' 的 Young GC 周期数（ZGC 非分代没有这行，恒为 0）");
        System.out.println("  ages         年龄表里出现过的 '- age N:' 行数，为 0 说明 Survivor 一次都没留下东西");
        System.out.println("  desurvKB     Survivor 的期望容量 = Eden/(SurvivorRatio+2)×2，日志里的 Desired survivor size");
        System.out.println("  thresholds   观测到的动态晋升阈值集合；出现 1 就意味着对象熬过一轮立刻被搬走");
        System.out.println("  maxAge       年龄表里出现过的最大年龄；它永远 ≤ thresholds 的最小值，这正是提前晋升的证据");
        System.out.println("  survPeakKB   年龄表 total 列的最大值，即某次 Young GC 后 Survivor 的水位");
        System.out.println("  promotedMB   Young GC 搬进老年代的累计量（只算 Old 变大的增量；Serial/Parallel 读 Tenured/ParOldGen 行，G1 读 Old regions×region）。");
        System.out.println("               Pretenure 组是 0：对象「分配即在 Old」，根本不走这条搬运路径，不是丢数据");
        System.out.println("  oldEndMB     老年代观测到的最高水位，注意它可能被 Full GC 又打回去");
        System.out.println("  humong       G1 里以 (G1 Humongous Allocation) 为原因触发的 Young GC 次数");
        System.out.println("  evacF        Evacuation Failure 次数：想搬存活对象却没地方放，会升级成 Full GC");
        System.out.println("  fullGC       Pause Full 次数");
        System.out.println("  checksum     同一负载跨变体必须相同，不同说明 objectKb 改动改变了工作量");
        System.out.println();
        System.out.println("每个变体的取舍：");
        rows.forEach(row -> System.out.println("  " + row.variant().label() + "：" + row.variant().note()));
    }

    /**
     * 打印该变体日志里前 6 行年龄表原文。
     *
     * <p>为什么要贴原文：{@code thresholds} 和 {@code maxAge} 这些聚合列是解析器算出来的，
     * 解析器可能错；把日志原句一起交出来，读者可以自己核对正则读得对不对。</p>
     */
    private static void printAgeLines(Path logDir, String label) {
        List<Path> logs;
        try (var stream = Files.list(logDir)) {
            logs = stream.filter(p -> p.getFileName().toString().startsWith("age-" + label + "-"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            System.out.println("   (列目录失败: " + e.getMessage() + ")");
            return;
        }
        if (logs.isEmpty()) {
            System.out.println("   (找不到日志文件)");
            return;
        }
        try {
            Files.readString(logs.get(logs.size() - 1)).lines()
                    .filter(line -> line.contains("gc,age"))
                    .limit(6)
                    .forEach(line -> System.out.println("   " + line));
        } catch (IOException e) {
            System.out.println("   (读取失败: " + e.getMessage() + ")");
        }
    }

    /** 读不到日志返回空串：子进程没起来时表格里会是一行全 0，这本身就是结论。 */
    private static String readLog(Path logFile) {
        try {
            return Files.readString(logFile);
        } catch (IOException e) {
            System.err.println("[warn] 读不到 " + logFile + "：" + e.getMessage());
            return "";
        }
    }

    /** 与 GcCompareLab 共用 {@code <tmpdir>/demo-jvm-lab/}，方便实验完手工复查。 */
    private static Path labDir() {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "demo-jvm-lab");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建实验目录 " + dir + "：" + e.getMessage(), e);
        }
        return dir;
    }

    private AgePromotionLab() {
    }
}
