package com.example.demo.jvm.gc;

import com.example.demo.jvm.lab.ChildJvm;
import com.example.demo.jvm.lab.LabArgs;
import com.example.demo.jvm.lab.LabReport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 场景4 的总入口：同一个负载分别在 Serial / Parallel / G1 / ZGC / ZGC 分代上跑一遍，
 * 解析各自的 GC 日志，打出一张可以直接比较的表。
 *
 * <p>为什么值得专门写一个编排类：GC 学习里最容易骗自己的就是「我换了收集器所以变快了」。
 * 只有把负载固定、把口径固定（STW = 日志里所有 {@code Pause} 行的时长之和），
 * 四行数字放在一起才有意义。这个类把两件事都锁死，并且用
 * {@code workload.checksum} 在每个变体间必须相等来证明「干的确实是同一份活」。</p>
 *
 * <p>关键实现：
 * <ul>
 *   <li>用 {@link ChildJvm} 起子进程，GC 参数只在启动时有效，常驻进程没法中途换收集器；</li>
 *   <li>GC 日志走 {@code -Xlog:gc*:file=<临时文件>}，由父进程读取后交给 {@link GcLogParser}，
 *       避免子进程读自己尚未刷完的日志；</li>
 *   <li>堆用 {@code -Xms=-Xmx} 固定，排除「堆自动扩容」这个最大干扰项。</li>
 * </ul></p>
 *
 * <p>坑：
 * <ul>
 *   <li>日志路径里不能出现冒号——{@code -Xlog} 用冒号分段，带 {@code :} 的路径会被当成分隔符解析；</li>
 *   <li>ZGC 在 JDK 21 里分代模式还是实验特性，必须同时给 {@code -XX:+UnlockExperimentalVMOptions}，
 *       否则子进程直接启动失败；</li>
 *   <li>{@code -XX:+UseXXXGC} 拼错不会报错，只会静默回落到默认收集器，
 *       所以表里附带 {@code gc.beans}，一眼能确认参数是否真的生效。</li>
 * </ul></p>
 */
public final class GcCompareLab {

    /** 每个收集器一行；flags 只放收集器相关的，堆与日志由本类统一加，保证公平。 */
    private record Variant(String label, List<String> gcFlags, String note) {
    }

    /** 一个变体跑完后的汇总行。 */
    private record Row(Variant variant,
                       GcLogParser.GcSummary summary,
                       Map<String, String> report,
                       int exitCode,
                       long elapsedMs) {

        String checksum() {
            return report.getOrDefault("workload.checksum", "-");
        }

        double wallMs() {
            return Double.parseDouble(report.getOrDefault("workload.wallMs", "0"));
        }

        double allocatedMb() {
            return Double.parseDouble(report.getOrDefault("workload.allocatedMb", "0"));
        }

        String gcBeans() {
            return report.getOrDefault("gc.beans", "-");
        }
    }

    /**
     * 五个收集器挨个起子进程跑同一份负载，最后打一张对比表。
     *
     * <p>用法：{@code java -cp demo-jvm/target/classes com.example.demo.jvm.gc.GcCompareLab
     * --heapMb=256 --rounds=40 --churnMb=16 --liveMb=8 --objectKb=32 --detail=false}。
     * 注意 {@code --heapMb} 会同时传给每个子进程，改它等于换了一台机器，
     * 想比较「堆大小对 Full GC 的影响」就固定收集器、只改这一个参数。</p>
     */
    public static void main(String[] args) {
        LabArgs parsed = LabArgs.parse(args);
        int heapMb = parsed.number("heapMb", 256);
        int rounds = parsed.number("rounds", 40);
        int churnMb = parsed.number("churnMb", 16);
        int liveMb = parsed.number("liveMb", 8);
        int objectKb = parsed.number("objectKb", 32);
        boolean detail = parsed.bool("detail", false);

        Path logDir = labDir();
        List<Row> rows = new ArrayList<>();
        for (Variant variant : variants()) {
            rows.add(runVariant(variant, logDir, heapMb, rounds, churnMb, liveMb, objectKb));
        }

        printTable(rows, heapMb);
        if (detail) {
            rows.forEach(row -> {
                System.out.println();
                System.out.println("## " + row.variant().label() + " 最长停顿（原文来自 " + row.variant().label() + " 的 gc.log）");
                row.summary().longestPauses().forEach(line -> System.out.println("   " + line));
            });
        }
    }

    private static List<Variant> variants() {
        return List.of(
                new Variant("Serial", List.of("-XX:+UseSerialGC"),
                        "单线程，全 STW；小堆、容器里单核、进程启动快是它唯一的优势"),
                new Variant("Parallel", List.of("-XX:+UseParallelGC"),
                        "多线程 Young GC，吞吐量优先；JDK 8 时代容器默认"),
                new Variant("G1", List.of("-XX:+UseG1GC"),
                        "分区 + 增量回收，JDK 9 起的默认；停顿目标 -XX:MaxGCPauseMillis"),
                new Variant("ZGC", List.of("-XX:+UseZGC"),
                        "JDK 21 的非分代 ZGC，几乎全部并发，停顿与堆大小无关"),
                new Variant("ZGC-Gen", List.of("-XX:+UnlockExperimentalVMOptions", "-XX:+UseZGC", "-XX:+ZGenerational"),
                        "JDK 21 的实验性分代 ZGC，短命对象回收效率明显更好"));
    }

    private static Row runVariant(Variant variant, Path logDir, int heapMb,
                                  int rounds, int churnMb, int liveMb, int objectKb) {
        Path logFile = logDir.resolve("gc-" + variant.label() + "-" + System.nanoTime() + ".log");
        List<String> flags = new ArrayList<>();
        // -Xms = -Xmx：固定堆大小，否则「堆先扩容再回收」会让不同收集器的 GC 次数失去可比性
        flags.add("-Xms" + heapMb + "m");
        flags.add("-Xmx" + heapMb + "m");
        flags.addAll(variant.gcFlags());
        // gc* 而不是 gc：ZGC 主日志里没有 Pause 行，只有 gc,phases 里才有停顿明细
        flags.add("-Xlog:gc*:file=" + logFile);

        List<String> labArgs = List.of(
                "--collector=" + variant.label(),
                "--rounds=" + rounds,
                "--churnMb=" + churnMb,
                "--liveMb=" + liveMb,
                "--objectKb=" + objectKb);
        // 显式给超时：ZGC 启动 + 全并发回收的收尾比 Serial 慢，120 秒足够又不至于挂死
        ChildJvm.Result result = ChildJvm.run(flags, GcLab.class.getName(), labArgs, 120_000);

        GcLogParser.GcSummary summary = GcLogParser.parse(variant.label(), readLog(logFile));
        // 表格里用的是挂钟时间（含 JVM 启动的开销另算），吞吐率才有意义
        return new Row(variant, summary, result.report(), result.exitCode(), result.elapsedMs());
    }

    private static void printTable(List<Row> rows, int heapMb) {
        System.out.println("# lab=gc-compare  heap=-Xms" + heapMb + "m -Xmx" + heapMb
                + "m  workload=" + rows.get(0).allocatedMb() + "MB allocated / "
                + rows.get(0).report().get("rounds") + " rounds");
        System.out.println();
        System.out.printf("%-10s %6s %6s %10s %9s %8s %8s %6s %8s %12s%n",
                "collector", "cycles", "stw", "totalMs", "maxMs", "tput%", "peakMB", "stall", "wallMs", "checksum");
        String expected = rows.get(0).checksum();
        for (Row row : rows) {
            GcLogParser.GcSummary summary = row.summary();
            System.out.printf("%-10s %6d %6d %10.2f %9.2f %8.2f %8d %6d %8.0f %12s%s%n",
                    summary.collector(), summary.cycles(), summary.stwPauses(),
                    summary.totalPauseMs(), summary.maxPauseMs(),
                    summary.throughputPercent((long) row.wallMs()),
                    summary.peakHeapBeforeMb(), summary.allocationStalls(),
                    row.wallMs(), row.checksum(),
                    expected.equals(row.checksum()) ? "" : "  <!=工作量不一致");
        }
        System.out.println();
        System.out.println("字段说明");
        System.out.println("  cycles   GC 周期数，按日志里 GC(n) 的 n 去重（一次周期可能有多行）");
        System.out.println("  stw      STW 停顿行数：Serial/Parallel/G1 每个周期一行，ZGC 一个周期有 3 个 Pause 阶段");
        System.out.println("  totalMs  所有 Pause 行时长之和，也就是应用真正停下来多久");
        System.out.println("  maxMs    最长单次停顿，衡量卡顿感的指标");
        System.out.println("  tput%    吞吐率 = 1 - totalMs / workload.wallMs，负载期间的有效工作时间占比");
        System.out.println("  peakMB   回收前堆使用量的最大值；接近 -Xmx 说明堆给少了");
        System.out.println("  stall    Allocation Stall 次数：应用线程在分配点上等内存，ZGC 堆不足的直接证据");
        System.out.println("  checksum 确定性校验和，各collector必须相同，否则两次跑的不是同一份工作");
        System.out.println();
        System.out.println("实际注册的收集器（参数拼错会回落默认值，靠这行确认生效）：");
        rows.forEach(row -> System.out.println("  " + row.variant().label() + " -> " + row.gcBeans()));
        System.out.println();
        System.out.println("每个变体的取舍：");
        rows.forEach(row -> System.out.println("  " + row.variant().label() + "：" + row.variant().note()));
    }

    /**
     * 读日志。文件不存在时返回空串——这本身就是一个重要信号：
     * 子 JVM 连启动都没成功（比如实验性参数没解锁），表格里会显示全 0 行。
     */
    private static String readLog(Path logFile) {
        try {
            return Files.readString(logFile);
        } catch (IOException e) {
            System.err.println("[warn] 读不到 " + logFile + "：" + e.getMessage());
            return "";
        }
    }

    /** 日志与 dump 统一放 {@code <tmpdir>/demo-jvm-lab/}，方便实验完手工复查。 */
    private static Path labDir() {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "demo-jvm-lab");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建实验目录 " + dir + "：" + e.getMessage(), e);
        }
        return dir;
    }

    private GcCompareLab() {
    }
}
