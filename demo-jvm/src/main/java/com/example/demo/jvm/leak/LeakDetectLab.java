package com.example.demo.jvm.leak;

import com.example.demo.jvm.lab.ChildJvm;
import com.example.demo.jvm.lab.LabArgs;
import com.example.demo.jvm.support.ClassHistogram;
import com.example.demo.jvm.support.Units;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 场景6 的总入口：一边让子进程活着漏，一边从外面敲 {@code jcmd} 取证。
 *
 * <p>为什么这个场景值得单独一个类：内存泄漏是唯一一个「必须在运行中观测」的内存问题。
 * OOM 可以等它炸、GC 行为可以看日志，但泄漏要看的是
 * <strong>同一进程在两个时刻的堆内容差</strong>——只能靠 {@code jcmd <pid> GC.class_histogram}
 * 隔一段时间拍一张。本类把「拍两张 + 求差」做成自动化，读者只要换 {@code --case}。</p>
 *
 * <p>关键实现：
 * <ul>
 *   <li>用 {@link ChildJvm#start} 起一个听 stdin 指令的子进程，
 *       父进程 {@code await("READY phase=1")} → 拍直方图 → {@code send("GO")} → 再拍；</li>
 *   <li>直方图用不带 {@code -all} 的 {@code GC.class_histogram}，也就是<em>只统计可达对象</em>，
 *       等价于 {@code jmap -histo:live} 但不需要额外触发 Full GC；</li>
 *   <li>每个 case 都跑「泄漏版」和「修好版」两遍，差值放在一起才叫调优前后对比。</li>
 * </ul></p>
 *
 * <p>坑：
 * <ul>
 *   <li>{@code GC.class_histogram} <strong>没有 {@code -live} 选项</strong>（实测报
 *       {@code Unknown argument '-live'}），只有 {@code -all} 与 {@code -parallel}；
 *       想要「只算存活」就<em>不要</em>传 {@code -all}，想连垃圾一起看才传；</li>
 *   <li>jcmd 输出第一行是 {@code <pid>:}，解析器要能跳过它，否则会把「30019:」当表头；</li>
 *   <li>修好版的 Δ 不会是 0：负载照跑，只是不再留住引用，
 *       所以涨的是些微的运行时对象。Δ 完全为 0 反而说明活儿没干活。</li>
 * </ul></p>
 *
 * <p>第 4 个案例（{@code intern}）实测推翻了常见说法，所以额外拍 {@code VM.stringtable}：
 * JDK 21 里 intern 表的条目是<strong>弱引用</strong>，只要字符串本身没有被别处抓住，
 * 一次 Full GC 就会把 20 万条全部清掉（本机实测 {@code 200053 -> 50} 条），
 * 可达堆总量一点没涨——所以「intern 一定泄漏」是 JDK 6 时代 PermGen 的旧结论。
 * 真正回不去的是桶数组：它从 65536 个桶扩到 131072 个（1 MB 原生内存），
 * 进程活着就不再缩小。这段证据在 {@link #printStringTableProof} 里单独成表。</p>
 */
public final class LeakDetectLab {

    /**
     * 一个待观察的 case 和它对应的修法说明。
     *
     * @param itemsFactor 条目数倍率。前三种泄漏每条就是几十 KB，200 条足够在直方图里显形；
     *                    {@code intern} 每条只有几十字节，必须放大几个数量级才看得见，
     *                    否则「没有增长」会被误读成「intern 不漏」
     */
    private record Variant(String caseName, String fixNote, int itemsFactor) {
    }

    /** 一次直方图快照：总量 + 每个类的字节数。 */
    private record Snapshot(double totalMb, Map<String, Long> bytesByClass) {
    }

    /**
     * intern 表快照，取自 {@code jcmd <pid> VM.stringtable} 的 {@code StringTable statistics} 段。
     *
     * @param entries 表里的字符串条数
     * @param bucketBytes 桶数组占用的<em>原生内存</em>字节数（不在堆里，直方图看不到）
     */
    private record StringTable(long entries, long bucketBytes) {
    }

    /**
     * 一个阶段的取证结果。
     *
     * <p>顺序是有意的：先读 intern 表（GC 前），再拍可达直方图——拍直方图本身会触发一次
     * Full GC，之后再读一次 intern 表。<strong>同一次 Full GC 前后</strong>的两个条数一比，
     * 就能直接看出 intern 表是不是在替进程扣住内存，而不需要相信任何人的说法。</p>
     */
    private record Probe(StringTable beforeGc, Snapshot live, StringTable afterGc) {
    }

    /** 一行结果。 */
    private record Row(String caseName, boolean fixed, Probe phase1, Probe phase2,
                       String readyLine1, String readyLine2, int exitCode) {

        double growthMb() {
            return phase2.live().totalMb() - phase1.live().totalMb();
        }
    }

    /**
     * 对每个（或 {@code --cases} 指定的几个）case 跑「泄漏版 + 修好版」，各拍两张直方图后打表。
     *
     * <p>用法：{@code java -cp demo-jvm/target/classes com.example.demo.jvm.leak.LeakDetectLab
     * --heapMb=256 --items=200 --chunkKb=64 --cacheLimit=64 --cases=staticCollection,threadLocal
     * --detail=true --dump=true}。不带 {@code --cases} 就是全部四个 case，
     * 要起 8 个子进程、拍 16 张直方图，本机约一分多钟。</p>
     *
     * <p>{@code --items} 是<strong>基础值</strong>：intern 案例内部乘 500，
     * 因为一条 intern 键才几十字节，200 条在直方图里根本看不见。</p>
     *
     * <p>{@code --dump=true} 会额外演示一次 {@code jcmd <pid> GC.heap_dump}，
     * 打印 jcmd 的原始回执（文件大小与耗时）后<em>立刻删除</em> hprof——一次转储就是几十 MB，
     * 留着会把磁盘吃掉，而本实验要的只是「这条路走得通」。</p>
     */
    public static void main(String[] args) {
        LabArgs parsed = LabArgs.parse(args);
        // 256 MB：够漏 4 轮 200×64KB（约 25 MB）又不会跟物理内存过不去
        int heapMb = parsed.number("heapMb", 256);
        int items = parsed.number("items", 200);
        int chunkKb = parsed.number("chunkKb", 64);
        boolean detail = parsed.bool("detail", false);
        // LRU 上限，只影响 unboundedCache 的修好版；也决定它「稳定后」占多少堆
        int cacheLimit = parsed.number("cacheLimit", 64);
        // --dump=true 时额外演示一次 jcmd GC.heap_dump（跑完就删，不留 100 MB 的 hprof）
        boolean dump = parsed.bool("dump", false);

        List<Variant> variants = List.of(
                new Variant("staticCollection", "static 集合只进不出 / 修好：处理完立刻释放引用", 1),
                new Variant("threadLocal", "线程池里 ThreadLocal 不 remove / 修好：finally 里 remove()", 1),
                new Variant("unboundedCache", "缓存不设上限 / 修好：LinkedHashMap LRU，上限见 --cacheLimit", 1),
                // 500 倍：每条只有几十字节，200 条时直方图上的差值会淹没在噪声里
                new Variant("intern", "运行期拼出来的字符串 intern() / 修好：不 intern", 500));

        List<Row> rows = new ArrayList<>();
        // --cases=staticCollection,threadLocal 只跑指定的几个：整套要起 8 个子进程、
        // 每个拍两次直方图（jcmd 走 attach，一次约 0.3 秒），全量跑一分多钟；
        // 测试只关心「泄漏版按阶段累加、修好版持平」这条不变量，用得起单个 case
        String only = parsed.string("cases", "");
        List<Variant> selected = variants.stream()
                .filter(variant -> only.isBlank()
                        || List.of(only.split(",")).contains(variant.caseName().trim()))
                .toList();
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("--cases=" + only + " 一个都没匹配上，可选："
                    + variants.stream().map(Variant::caseName).toList());
        }
        for (Variant variant : selected) {
            int caseItems = items * variant.itemsFactor();
            // 每个 case 跑两遍：先泄漏版再修好版，读者一眼看到「同样的活儿，留不留引用的差别」
            rows.add(runCase(variant.caseName(), false, heapMb, caseItems, chunkKb, cacheLimit));
            rows.add(runCase(variant.caseName(), true, heapMb, caseItems, chunkKb, cacheLimit));
            if (dump) {
                dumpHeapOnce(variant.caseName(), heapMb, caseItems, chunkKb);
            }
        }
        printTable(rows, heapMb, items, chunkKb, cacheLimit, selected);
        printStringTableProof(rows);
        if (detail) {
            rows.forEach(row -> {
                System.out.println();
                System.out.println("## " + row.caseName() + " fixed=" + row.fixed()
                        + "（子进程 pid 见 stderr 的 jcmd 命令行）");
                System.out.println("   phase1 " + row.readyLine1());
                System.out.println("   phase2 " + row.readyLine2());
            });
        }
    }

    /**
     * 起子进程、拍两张直方图、收工。
     *
     * @param cacheLimit 只影响 {@code unboundedCache} 的修好版：LRU 上限
     */
    private static Row runCase(String caseName, boolean fixed, int heapMb,
                               int items, int chunkKb, int cacheLimit) {
        List<String> flags = List.of("-Xms" + heapMb + "m", "-Xmx" + heapMb + "m");
        List<String> labArgs = List.of("--case=" + caseName,
                "--fix=" + fixed,
                "--items=" + items,
                "--chunkKb=" + chunkKb,
                "--cacheLimit=" + cacheLimit);
        try (ChildJvm.Session session = ChildJvm.start(flags, LeakLab.class.getName(), labArgs)) {
            // 等 READY 而不是 sleep 固定时长：分配快慢随机器变，写死睡眠会让第一张图拍到半成品
            String ready1 = session.await("READY phase=1", 90_000);
            Probe first = probe(session.pid());
            session.send("GO");
            String ready2 = session.await("READY phase=2", 90_000);
            Probe second = probe(session.pid());
            session.send("EXIT");
            int exit = session.waitForExit(20_000);
            return new Row(caseName, fixed, first, second, ready1, ready2, exit);
        }
    }

    /**
     * 对一个还活着的子进程做一次完整取证：intern 表 → 可达直方图 → 再看 intern 表。
     *
     * <p>中间那次直方图顺带触发的 Full GC 是白捡的：它正好把「Full GC 能不能清掉 intern」
     * 变成前后两个数字，而不是要去翻 HotSpot 源码。</p>
     */
    private static Probe probe(long pid) {
        StringTable beforeGc = stringTable(pid);
        Snapshot live = snapshot(histogram(pid));
        return new Probe(beforeGc, live, stringTable(pid));
    }

    /** {@code jcmd <pid> VM.stringtable}，只取 {@code StringTable statistics} 段（后面的 Shared 段忽略）。 */
    private static StringTable stringTable(long pid) {
        ChildJvm.Result result = ChildJvm.runCommand(
                List.of(ChildJvm.jdkTool("jcmd"), String.valueOf(pid), "VM.stringtable"), 60_000);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("jcmd VM.stringtable 失败（pid=" + pid + "）："
                    + result.stderr().strip());
        }
        String text = result.stdout();
        // 遇到 Shared 段就停：它统计的是 CDS 共享字符串，和运行期 intern 出来的键不是一回事
        int sharedAt = text.indexOf("Shared String Table");
        String own = sharedAt < 0 ? text : text.substring(0, sharedAt);
        return new StringTable(firstLong(ENTRIES_COUNT, own), firstLong(BUCKET_BYTES, own));
    }

    /**
     * 桶数组的字节数在 {@code Number of buckets : 131072 = 1048576 bytes} 的 {@code =} 后面。
     *
     * <p>取错位置会把 13 万个桶当成 13 万字节，差 8 倍——每个桶是一个 8 字节指针；
     * 而这一项是唯一在 Full GC 之后还回不去的数字，所以必须解析对。</p>
     */
    private static final java.util.regex.Pattern BUCKET_BYTES =
            java.util.regex.Pattern.compile("Number of buckets\\s*:\\s*\\d+\\s*=\\s*(\\d+)\\s*bytes");

    /** {@code Number of entries : 200053 = 3200848 bytes} 里冒号后的条数。 */
    private static final java.util.regex.Pattern ENTRIES_COUNT =
            java.util.regex.Pattern.compile("Number of entries\\s*:\\s*(\\d+)");

    private static long firstLong(java.util.regex.Pattern pattern, String text) {
        java.util.regex.Matcher matcher = pattern.matcher(text);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0L;
    }

    /**
     * 从进程外拍一张类直方图。
     *
     * <p>这里刻意走真正的 {@code jcmd} 可执行文件而不是 MBean：本场景要教的就是
     * 「线上只有 jcmd 时怎么办」，用进程内 MBean 反而把要演示的东西藏起来了。</p>
     */
    private static String histogram(long pid) {
        ChildJvm.Result result = ChildJvm.runCommand(
                List.of(ChildJvm.jdkTool("jcmd"), String.valueOf(pid), "GC.class_histogram"), 60_000);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("jcmd GC.class_histogram 失败（pid=" + pid + "）："
                    + result.stderr().strip());
        }
        return result.stdout();
    }

    /**
     * 额外演示：{@code jcmd <pid> GC.heap_dump} 导出活堆，然后把文件删掉。
     *
     * <p>为什么要留着原文：MAT/JProfiler 都要装，而 {@code GC.heap_dump} 是 JDK 自带的，
     * 容器里没有 GUI 时它和直方图就是全部家当。打印 jcmd 的原始回执（含文件大小与耗时），
     * 读者能确认这条路真的通。</p>
     */
    private static void dumpHeapOnce(String caseName, int heapMb, int items, int chunkKb) {
        List<String> flags = List.of("-Xms" + heapMb + "m", "-Xmx" + heapMb + "m");
        List<String> labArgs = List.of("--case=" + caseName, "--fix=false",
                "--items=" + items, "--chunkKb=" + chunkKb);
        try (ChildJvm.Session session = ChildJvm.start(flags, LeakLab.class.getName(), labArgs)) {
            session.await("READY phase=1", 90_000);
            String target = System.getProperty("java.io.tmpdir") + "/demo-jvm-lab/manual-" + caseName + ".hprof";
            ChildJvm.Result result = ChildJvm.runCommand(
                    List.of(ChildJvm.jdkTool("jcmd"), String.valueOf(session.pid()), "GC.heap_dump", target),
                    120_000);
            System.out.println("[heap-dump] case=" + caseName + " " + result.stdout().replace('\n', ' ').strip());
            session.send("EXIT");
            session.waitForExit(20_000);
            // 立刻删：一次 256 MB 堆的转储就是一张 90 MB+ 的文件，留着会把磁盘吃掉
            deleteQuietly(target);
        }
    }

    private static void deleteQuietly(String path) {
        try {
            java.nio.file.Files.deleteIfExists(java.nio.file.Path.of(path));
        } catch (java.io.IOException e) {
            System.err.println("[warn] 临时 hprof 没删掉 " + path + "：" + e.getMessage());
        }
    }

    private static Snapshot snapshot(String histogramText) {
        Map<String, Long> byClass = new LinkedHashMap<>();
        // 直接用 limit=Integer.MAX_VALUE 拿全量：求差值要看的是「谁涨得最多」，
        // 只看前 12 名会漏掉那些从 0 涨到第 30 名的类，而那恰恰是泄漏本体
        for (ClassHistogram.Entry entry : ClassHistogram.parse(histogramText, Integer.MAX_VALUE)) {
            byClass.put(entry.className(), entry.bytes());
        }
        return new Snapshot(Units.mb(ClassHistogram.totalBytes(histogramText)), byClass);
    }

    private static void printTable(List<Row> rows, int heapMb, int items, int chunkKb,
                                   int cacheLimit, List<Variant> variants) {
        System.out.println("# lab=leak-detect  heap=-Xmx" + heapMb + "m  每阶段基础 items=" + items
                + " × " + chunkKb + "KB（intern 案例按倍率放大，见下）  观测=jcmd <pid> GC.class_histogram（只算可达对象）");
        System.out.println();
        System.out.printf("%-18s %-6s %10s %10s %10s %-26s %-26s%n",
                "case", "fixed", "live1MB", "live2MB", "growthMB", "topGrowth#1", "topGrowth#2");
        for (Row row : rows) {
            System.out.printf("%-18s %-6s %10.2f %10.2f %10.2f %-26s %-26s%n",
                    row.caseName(), row.fixed(), row.phase1().live().totalMb(), row.phase2().live().totalMb(),
                    row.growthMb(), describeDelta(row, 1), describeDelta(row, 2));
        }
        System.out.println();
        System.out.println("字段说明");
        System.out.println("  live1MB    阶段1 结束时可达对象总量（MB）");
        System.out.println("  live2MB    阶段2 结束时可达对象总量（MB）——泄漏版按阶段累加，修好版≈持平");
        System.out.println("  growthMB   两者之差，就是这一阶段真正留住的新增内存");
        System.out.println("  topGrowth  按类求差后涨幅最大的类，[B 就是 byte[]；显示 none 说明没有明显的单类增长");
        System.out.println("  （jcmd 不带 -all 会先触发一次 Full GC，所以这里看不到已经变垃圾的部分。"
                + "另外 live1/live2 是「整个进程」的可达堆，里面压着约 1.3 MB 的运行时背景，"
                + "所以 3.2 MB 的载荷翻倍后读出来是 4.49→7.62 而不是 3.2→6.4——"
                + "要看纯载荷就减去同 case 修好版的 live1，本机实测比值正好回到 2.0）");
        System.out.println();
        System.out.println("每个 case 的修法与条目倍率（--cacheLimit=" + cacheLimit + "）：");
        variants.forEach(variant -> System.out.println("  " + variant.caseName()
                + " ×" + variant.itemsFactor() + "：" + variant.fixNote()));
    }

    /**
     * 单独成表的 intern 证据：同一次 Full GC 前后的 intern 表条数。
     *
     * <p>为什么值得单列一张表：主表只看可达堆，{@code intern} 那一行两个版本都是 0 增长，
     * 读者会以为「实验没跑起来」。加上条数就变成正面证据——条数确实冲到了 20 万，
     * 但 Full GC 一过就掉回几十条，说明这些东西本来就是可回收的。</p>
     */
    private static void printStringTableProof(List<Row> rows) {
        System.out.println();
        System.out.println("intern 表实测（jcmd <pid> VM.stringtable；GC前/后是同一次 Full GC 的两侧，"
                + "Full GC 由 GC.class_histogram 顺带触发）");
        System.out.printf("%n%-18s %-6s %12s %12s %12s %12s %12s %12s%n",
                "case", "fixed", "P1条数GC前", "P1条数GC后", "P2条数GC前", "P2条数GC后",
                "桶MB(P2前)", "桶MB(P2后)");
        for (Row row : rows) {
            System.out.printf("%-18s %-6s %12d %12d %12d %12d %12.2f %12.2f%n",
                    row.caseName(), row.fixed(),
                    row.phase1().beforeGc().entries(), row.phase1().afterGc().entries(),
                    row.phase2().beforeGc().entries(), row.phase2().afterGc().entries(),
                    Units.mb(row.phase2().beforeGc().bucketBytes()),
                    Units.mb(row.phase2().afterGc().bucketBytes()));
        }
        System.out.println();
        System.out.println("读法：");
        System.out.println("  条数 GC 前爆涨、GC 后掉回几十 → intern 表条目是弱引用，没人另外抓住就收得掉；");
        System.out.println("  桶数组是原生内存：堆直方图里看不到它，OOM 时也完全不出现，"
                + "但条数清空后它未必跟着缩回去（见最后两列）。");
    }

    /**
     * 找出两次快照之间增长最多的第 {@code rank} 个类，格式成「类名 +N.NN MB」。
     *
     * <p>为什么要 rank=2：第一名经常是 {@code [B}（载荷），第二名才是「容器本体」，
     * 例如 {@code java.util.ArrayList} 或 {@code java.lang.ThreadLocal$ThreadLocalMap$Entry}——
     * 只看第一名会知道漏了多大的东西，但不知道被谁抓住。</p>
     */
    private static String describeDelta(Row row, int rank) {
        List<Map.Entry<String, Long>> deltas = new ArrayList<>();
        row.phase2().live().bytesByClass().forEach((className, bytes) -> {
            long before = row.phase1().live().bytesByClass().getOrDefault(className, 0L);
            // 跳过 FillerElement：它是压缩出来的空隙对象，跟应用无关，
            // 让它占着第一名就永远看不到「被谁抓住」的那个真正的容器类
            if (bytes > before && !className.contains("FillerElement")) {
                deltas.add(Map.entry(className, bytes - before));
            }
        });
        deltas.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        if (deltas.size() < rank) {
            return "none";
        }
        Map.Entry<String, Long> picked = deltas.get(rank - 1);
        String shortName = picked.getKey().replaceAll("\\s*\\(.*\\)$", "");
        return shortName + " +" + String.format("%.2f", Units.mb(picked.getValue()));
    }

    private LeakDetectLab() {
    }
}
