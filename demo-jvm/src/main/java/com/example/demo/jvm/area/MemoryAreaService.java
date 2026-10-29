package com.example.demo.jvm.area;

import com.example.demo.jvm.support.DiagnosticCommands;
import com.example.demo.jvm.support.JvmFlags;
import com.example.demo.jvm.support.Units;
import com.sun.management.OperatingSystemMXBean;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 把「运行时数据区」从教材图变成可请求的实时数据。
 *
 * <p>定位：本模块唯一的观测核心。堆、方法区（元空间）、代码缓存、直接内存
 * 都能在 MXBean 里读到，程序计数器和虚拟机栈不能——接口把「读得到」和
 * 「读不到」分开返回，并在 {@code howToMeasure} 里说明该换什么工具，
 * 因为「看不见不等于不存在」正是初学者最容易建立错认知的地方。</p>
 *
 * <p>关键实现：
 * <ul>
 *   <li>内存池用 {@link MemoryPoolMXBean}，堆/非堆总用量用 {@code MemoryMXBean}；</li>
 *   <li>直接内存用 {@link BufferPoolMXBean}，上限从 {@code MaxDirectMemorySize} 读；</li>
 *   <li>线程与栈用 {@code ThreadMXBean} + {@code ThreadStackSize} 估算；</li>
 *   <li>真实的栈占用与本地内存只能来自 NMT，未开启时 {@code nativeMemory} 返回 enabled=false。</li>
 * </ul></p>
 *
 * <p>坑：GC 统计（{@code collectionCount}/{@code collectionTime}）是<strong>进程启动以来的累计值</strong>，
 * 不能当次请求的耗时读；要看一次分配引发了几次回收，必须先读一次再读一次取差值，
 * 本模块用 {@code /api/jvm/gc} 前后对照来讲这件事。</p>
 *
 * <p>替代方案：VisualVM / JDK Mission Control 能看到同样的数据，但无法在文档里做成
 * 一条命令一个结论，所以这里用 HTTP 暴露，配合 jcmd 与 jstat 交叉验证。</p>
 */
@Service
public class MemoryAreaService {

    /**
     * NMT 汇总里我们关心的分区。key 是 NMT 的段名，value 是给读者看的中文说明。
     * NMT 段名在不同 JDK 版本会有增减，所以匹配不到就跳过，不做硬校验。
     *
     * <p>为什么只挑这七段：{@code Thread} 对应虚拟机栈的真实开销，
     * {@code Class} 对应元空间，{@code Code} 对应 JIT 代码缓存，
     * {@code GC}/{@code Internal}/{@code Other}/{@code Symbol} 是「堆之外还有多少说不清的钱」——
     * 其余段（{@code Java Heap}、{@code Arena/Class} 之类）与内存池视图重复，
     * 列出来只会让读者以为有两套数字要核对。</p>
     */
    private static final List<String> NMT_SECTIONS = List.of("Thread", "Class", "Code", "GC", "Internal", "Other", "Symbol");
    /**
     * 段头行。NMT 用制表符对齐，段名后面可能跟 {@code (sub-section)} 或 {@code :}，
     * 所以正则只吃到「空格/括号/冒号」之前；{@code [A-Za-z/ ]} 允许 {@code Arena/Class} 这种带斜杠的名字。
     */
    private static final Pattern NMT_SECTION = Pattern.compile("^-\\s*([A-Za-z/ ]+?)(?:\\s*\\(|\\s*:)");
    /**
     * NMT 的单位后缀实测是大写 {@code KB}（JDK 21），但不同版本出现过 {@code kB} 写法，
     * 所以大小写不敏感匹配；曾经因为只匹配 {@code kB} 而把所有分区读成 0。
     */
    private static final Pattern COMMITTED = Pattern.compile("committed=(\\d+)KB", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOTAL_LINE =
            Pattern.compile("^Total:\\s*reserved=(\\d+)KB,\\s*committed=(\\d+)KB", Pattern.CASE_INSENSITIVE);

    private final JvmFlags flags;
    private final DiagnosticCommands diagnostics;

    /**
     * @param flags       启动参数读取器：-Xss / MaxDirectMemorySize / MaxMetaspaceSize 这些「上限」
     *                    在 MXBean 里是读不到的，只能回头问 JVM 自己，所以它是观测的第二个数据源
     * @param diagnostics 诊断命令通道（走 DiagnosticCommand MBean，等价于进程外的 jcmd）：
     *                    NMT 只能通过它拿，没有对应的 MXBean
     */
    public MemoryAreaService(JvmFlags flags, DiagnosticCommands diagnostics) {
        this.flags = flags;
        this.diagnostics = diagnostics;
    }

    /**
     * 完整快照，对应 {@code GET /api/jvm/areas}。
     *
     * <p>一次请求里 {@link #nativeMemory()} 会被 {@link #areaViews()} 和
     * {@link #threadView()} 各要一次，但真正的扫描只发生在 {@code areaViews} 内部——
     * 它把读好的结果当参数传给 {@code threadView(nmt)}，避免 NMT 这种全 JVM 扫描跑两遍。</p>
     */
    public AreaSnapshot snapshot() {
        return new AreaSnapshot(
                process(),
                areaViews(),
                poolViews(),
                collectorViews(),
                directMemory(),
                threadView(),
                nativeMemory(),
                flags.areaFlags());
    }

    /**
     * 只列内存池，对应 {@code GET /api/jvm/pools}，方便和 {@code -XX} 参数逐条对照。
     *
     * <p>返回顺序就是 {@code ManagementFactory.getMemoryPoolMXBeans()} 的顺序，也就是
     * HotSpot 注册它们的顺序（Eden → S0 → S1 → Old → Metaspace → Compressed Class → CodeHeap 三段）。
     * 刻意不排序：读者拿这份列表和 {@code jcmd VM.native_memory}、{@code jstat -gcutil} 的列
     * 对得上，比按字母排更有用。</p>
     */
    public List<AreaSnapshot.Pool> poolViews() {
        List<AreaSnapshot.Pool> pools = new ArrayList<>();
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            MemoryUsage usage = pool.getUsage();
            if (usage == null) {
                // 池刚创建或已被回收时可能读到 null，跳过比抛异常更诚实
                continue;
            }
            pools.add(new AreaSnapshot.Pool(
                    pool.getName(),
                    pool.getType() == MemoryType.HEAP ? "HEAP" : "NON_HEAP",
                    Units.mb(usage.getUsed()),
                    Units.mb(usage.getCommitted()),
                    // -1 表示「无硬上限」或「随 GC 动态伸缩」，翻译成 null 让读者不会当成 0 MB
                    usage.getMax() < 0 ? null : Units.mb(usage.getMax()),
                    // peakUsage 是「进程启动以来的最高值」，不会随 GC 回落，
                    // 用它回答「堆到底被用到过多少」比 used 更有说服力
                    Units.mb(pool.getPeakUsage().getUsed()),
                    usage.getMax() > 0 ? percent(usage.getUsed(), usage.getMax()) : null,
                    belongsTo(pool.getName())));
        }
        return pools;
    }

    /**
     * GC 累计统计，对应 {@code GET /api/jvm/gc}。
     *
     * <p>这里的 count/time 都是从进程启动开始累计的，所以「这次请求触发了几次 GC」
     * 必须靠前后两次相减得到——教程里所有 GC 对比表都是这么来的，接口本身不去做差。</p>
     */
    public List<AreaSnapshot.Collector> collectorViews() {
        List<AreaSnapshot.Collector> collectors = new ArrayList<>();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = bean.getCollectionCount();
            long time = bean.getCollectionTime();
            collectors.add(new AreaSnapshot.Collector(
                    bean.getName(),
                    // memoryPoolNames 是差值观察的键：想知道 Full GC 前老年代多少，
                    // 就用它去 /pools 里对上那几个池的名字
                    List.of(bean.getMemoryPoolNames()),
                    count,
                    time,
                    // 平均暂停只是估算：collectionTime 只累计 STW 时间，并发标记阶段不计入
                    count > 0 ? round((double) time / count) : null,
                    phase(bean.getName())));
        }
        return collectors;
    }

    /**
     * 线程与栈视图，对应 {@code GET /api/jvm/threads}。
     *
     * <p>公开的重载只负责「自己去读一次 NMT」；快照内部走带参数的私有版本，
     * 让一次 {@code /areas} 请求里 NMT 只扫一遍。</p>
     */
    public AreaSnapshot.Threads threadView() {
        return threadView(nativeMemory());
    }

    /**
     * @param nmt 已经读好的 NMT 汇总；单独抽出来是因为一次快照里多个视图都要用它，
     *            而 NMT 命令是全 JVM 扫描，不该在一个请求里重复执行
     */
    private AreaSnapshot.Threads threadView(AreaSnapshot.NativeMemory nmt) {
        var threads = ManagementFactory.getThreadMXBean();
        long stackBytes = flags.stackSizeBytes();
        // 估算口径：活跃线程数 × 单线程栈上限。真实提交内存通常更小，因为栈是按需 commit 的
        double estimatedMb = Units.mb((long) threads.getThreadCount() * stackBytes);
        long[] deadlocked = threads.findDeadlockedThreads();
        return new AreaSnapshot.Threads(
                threads.getThreadCount(),
                threads.getDaemonThreadCount(),
                threads.getPeakThreadCount(),
                threads.getTotalStartedThreadCount(),
                round(stackBytes / 1024.0),
                estimatedMb,
                // 未开启 NMT 时给出 null，明确区分「没有」和「看不到」
                nmt.enabled() && nmt.threadMb() > 0 ? nmt.threadMb() : null,
                deadlocked == null ? 0 : deadlocked.length);
    }

    /** 直接内存视图；BufferPoolMXBean 的 memoryUsed 是「已申请未释放」，不是峰值。 */
    public AreaSnapshot.DirectMemory directMemory() {
        BufferPoolMXBean direct = bufferPool("direct");
        BufferPoolMXBean mapped = bufferPool("mapped");
        long maxBytes = flags.maxDirectMemoryBytes();
        return new AreaSnapshot.DirectMemory(
                direct == null ? 0 : direct.getCount(),
                direct == null ? 0 : Units.mb(direct.getMemoryUsed()),
                direct == null ? 0 : Units.mb(direct.getTotalCapacity()),
                Units.mb(maxBytes),
                mapped == null ? 0 : mapped.getCount(),
                mapped == null ? 0 : Units.mb(mapped.getTotalCapacity()),
                // 明确告诉读者这个上限从哪来，否则「上限正好等于 -Xmx」会被误认为是巧合
                flags.number("MaxDirectMemorySize").orElse(0L) > 0
                        ? "-XX:MaxDirectMemorySize 显式设置"
                        : "未显式设置，JDK 按 -Xmx 作为上限");
    }

    /**
     * NMT 汇总（需要 {@code -XX:NativeMemoryTracking=summary} 启动）。
     *
     * <p>这里直接走 DiagnosticCommand MBean 而不是外部 jcmd，输出格式一致。
     * 未开启 NMT 时不抛异常：观测接口应当如实说明「看不看得见、为什么、怎么打开」。</p>
     */
    public AreaSnapshot.NativeMemory nativeMemory() {
        Optional<String> text = readNativeMemory();
        if (text.isEmpty()) {
            return new AreaSnapshot.NativeMemory(false, 0, 0, 0, 0, 0, 0, 0, 0,
                    "加 -XX:NativeMemoryTracking=summary 重启后，/api/jvm/areas 的 nativeMemory 与 /api/jvm/native-memory 才有数据");
        }
        Map<String, Double> committed = parseSections(text.get());
        double[] total = parseTotal(text.get());
        return new AreaSnapshot.NativeMemory(
                true,
                total[0],
                total[1],
                committed.getOrDefault("Thread", 0.0),
                committed.getOrDefault("Class", 0.0),
                committed.getOrDefault("Code", 0.0),
                committed.getOrDefault("GC", 0.0),
                committed.getOrDefault("Internal", 0.0),
                committed.getOrDefault("Other", 0.0),
                "NMT 统计的是 JVM 自己 malloc 的本地内存：Thread 段含线程栈，Class 段含元空间，"
                        + "Java Heap 段含堆。两者相加才接近 RSS。");
    }

    /**
     * 教材六大分区 + 直接内存的实时用量；读不到的区用 null + howToMeasure 说明。
     *
     * <p>顺序就是《深入理解 Java 虚拟机》里那张内存分区图的顺序：
     * 线程私有三区（PC / 虚拟机栈 / 本地方法栈）→ 线程共享两区（堆 / 方法区）→
     * 最后附一个「不属于运行时数据区」的直接内存。读不到的区一律
     * {@code usedMb/committedMb = null}，靠 {@code howToMeasure} 说明该换什么工具——
     * 因为「PC 寄存器看不见」这件事本身就是考点，接口不给这一行，
     * 读者会以为它不存在，或者去别处找一个根本不存在的监控项。</p>
     *
     * <p>{@code usedMb} 与 {@code committedMb} 的语义在每个区都不一样，
     * 所以两者的差别写在各区的 {@code remark} 里，而不是统一承诺「committed 一定大于 used」。</p>
     */
    private List<AreaSnapshot.Area> areaViews() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        AreaSnapshot.NativeMemory nmt = nativeMemory();
        AreaSnapshot.Threads threads = threadView(nmt);
        AreaSnapshot.DirectMemory direct = directMemory();

        List<AreaSnapshot.Area> areas = new ArrayList<>();
        areas.add(new AreaSnapshot.Area(
                "程序计数器（PC 寄存器）",
                "线程私有",
                "当前字节码指令地址；遇到 JIT 编译后的本地代码时记 undefined",
                "读不到：HotSpot 把它放在 JavaThread 的原生结构里，不单独成块内存",
                null, null, null,
                "唯一没有 OOM 的区（规范里它甚至可能实现为寄存器）"));
        areas.add(new AreaSnapshot.Area(
                "Java 虚拟机栈",
                "线程私有",
                "栈帧：局部变量表、操作数栈、动态链接、方法返回地址",
                "-Xss × 活跃线程数（估算）；真实值看 NMT 的 Thread 段",
                // 估算值不是提交值，读者拿它和 RSS 对比时会差很多，这里同时给出 NMT 值做校准
                threads.estimatedStackTotalMb(),
                nmt.enabled() ? nmt.threadMb() : null,
                null,
                "usedMb 是 ThreadMXBean 的 Java 线程数 × -Xss 的估算，committedMb 是 NMT 实测；"
                        + "实测通常更大，因为 GC、JIT、监视线程这些 JVM 内部线程不出现在 ThreadMXBean 里。"
                        + "递归过深抛 StackOverflowError（可用 -Xss 调）；线程开太多申请不到栈会抛"
                        + " OutOfMemoryError: unable to create native thread"));
        areas.add(new AreaSnapshot.Area(
                "本地方法栈",
                "线程私有",
                "Native 方法的调用帧",
                "HotSpot 与 Java 虚拟机栈合并实现，因此没有独立数字",
                null, null, null,
                "「HotSpot 把两个栈合成一个」是常见考点，也是它没有单独监控项的原因"));
        areas.add(new AreaSnapshot.Area(
                "堆",
                "线程共享",
                "对象实例、数组、（JDK8+）字符串常量池里的 String 对象",
                "MemoryMXBean.getHeapMemoryUsage() + 各分代内存池",
                Units.mb(heap.getUsed()),
                Units.mb(heap.getCommitted()),
                heap.getMax() < 0 ? null : Units.mb(heap.getMax()),
                "存放失败才谈逃逸：能栈上分配/标量替换的对象不会进堆"));
        areas.add(new AreaSnapshot.Area(
                "方法区（HotSpot 用元空间实现）",
                "线程共享",
                "类元信息、字段与方法的结构描述、运行时常量池、静态变量的引用",
                "内存池 Metaspace（+ Compressed Class Space）",
                metaspaceUsedMb(),
                metaspaceCommittedMb(),
                // 未设置 -XX:MaxMetaspaceSize 时元空间没有硬上限，返回 null 而不是 0
                optionalFlagMb("MaxMetaspaceSize"),
                "JDK7 起运行时常量池随类移到元空间；类加载器存活则元空间不回收，"
                        + "动态生成类（代理、Groovy、字节码增强）不加 -XX:MaxMetaspaceSize 会吃光本地内存"));
        areas.add(new AreaSnapshot.Area(
                "即时编译代码缓存",
                "线程共享",
                "JIT 编译后的机器码（分 non-nmethods / profiled / non-profiled 三段）",
                "三个 CodeHeap 内存池 + ReservedCodeCacheSize",
                codeHeapUsedMb(),
                codeHeapCommittedMb(),
                optionalFlagMb("ReservedCodeCacheSize"),
                "代码缓存满了不再报错，只是停止 JIT，性能悄悄回落到解释执行——"
                        + "所以排查「越跑越慢」时它是候选项（jcmd VM.compiler stats）"));
        areas.add(new AreaSnapshot.Area(
                "直接内存（不属于运行时数据区）",
                "线程共享",
                "NIO DirectByteBuffer、Netty 池化内存、mmap 映射区",
                "BufferPoolMXBean 的 memoryUsed，上限取 MaxDirectMemorySize",
                direct.directUsedMb(),
                direct.directCapacityMb(),
                direct.maxMb(),
                "不在堆里，所以 GC 只在 DirectByteBuffer 被回收时才顺带释放它——"
                        + "堆很健康而进程 RSS 爆掉通常是这里"));
        return areas;
    }

    /**
     * 进程身份，对应 {@code GET /api/jvm/self}。
     *
     * <p>{@code pid} 是这个方法存在的首要理由：教程里每一条 {@code jcmd}/{@code jstat}
     * 命令的第一个参数都是它。其余字段（JVM 名、规范版本、启动参数）用来确认
     * 「我连上的确实是这个进程」，避免在容器里 attach 到 sidecar 的 JVM 上取错数据。</p>
     */
    private AreaSnapshot.Process process() {
        var runtime = ManagementFactory.getRuntimeMXBean();
        OperatingSystemMXBean os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean.class);
        return new AreaSnapshot.Process(
                ProcessHandle.current().pid(),
                runtime.getSpecVersion(),
                runtime.getVmName(),
                gcAlgorithm(),
                runtime.getUptime(),
                Runtime.getRuntime().availableProcessors(),
                Units.mb(os.getTotalMemorySize()),
                Units.mb(os.getFreeMemorySize()),
                runtime.getInputArguments());
    }

    /**
     * 判断当前用的是哪个收集器。
     *
     * <p>先看 -XX 布尔旗标（实测 HotSpot 的 getVMOption 对布尔参数返回 "true"/"false"），
     * 全部为 false 时退回收集器名字判断，这样在 OpenJ9 之类没有这些旗标的 JVM 上也能给出结论。</p>
     */
    private String gcAlgorithm() {
        if ("true".equalsIgnoreCase(flags.raw("UseZGC"))) {
            // JDK 21 的分代 ZGC 需要额外旗标，两代/非两代的日志与统计差别很大，必须区分
            return "true".equalsIgnoreCase(flags.raw("ZGenerational")) ? "ZGC（分代）" : "ZGC（JDK 21 默认非分代）";
        }
        if ("true".equalsIgnoreCase(flags.raw("UseShenandoahGC"))) {
            return "Shenandoah";
        }
        if ("true".equalsIgnoreCase(flags.raw("UseSerialGC"))) {
            return "Serial + Serial Old";
        }
        if ("true".equalsIgnoreCase(flags.raw("UseParallelGC"))) {
            return "Parallel Scavenge + Parallel Old";
        }
        if ("true".equalsIgnoreCase(flags.raw("UseG1GC"))) {
            return "G1";
        }
        // 兜底：按收集器名字反推
        String names = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName)
                .reduce("", (a, b) -> a + " " + b);
        if (names.contains("ZGC")) {
            return "ZGC";
        }
        if (names.contains("PS ")) {
            return "Parallel Scavenge + Parallel Old";
        }
        if (names.contains("MarkSweepCompact")) {
            return "Serial + Serial Old";
        }
        return names.isBlank() ? "未知" : "G1" + (names.contains("G1") ? "" : "（按收集器名推断）");
    }

    /**
     * 收集器名字 -&gt; 阶段说明。名字是 HotSpot 硬编码的，四种收集器的对应关系如下：
     * Copy/PS Scavenge/G1 Young 是年轻代 STW；MarkSweepCompact/PS MarkSweep/G1 Old 是老年代（Full）；
     * G1 Concurrent GC 与 ZGC Cycles 是并发阶段，不计入 STW 时长。
     */
    private static String phase(String collectorName) {
        String name = collectorName.toLowerCase();
        if (name.contains("concurrent") || name.contains("cycles")) {
            return "并发阶段（不计入 STW 时长）";
        }
        if (name.contains("pause") || name.contains("pauses")) {
            return "停顿阶段（STW，累计时长只看这里）";
        }
        if (name.contains("young") || name.contains("scavenge") || name.equals("copy") || name.contains("minor")) {
            return "年轻代 STW";
        }
        if (name.contains("old") || name.contains("marksweep") || name.contains("marksweepcompact") || name.contains("major")) {
            return "老年代 / Full GC（STW）";
        }
        return "未识别阶段";
    }

    /** 池名到「属于教材哪个区」的翻译，读者最常问的就是这个。 */
    private static String belongsTo(String poolName) {
        String name = poolName.toLowerCase();
        if (name.contains("eden")) {
            return "堆 · 新生代 Eden";
        }
        if (name.contains("survivor")) {
            return "堆 · 新生代 Survivor（S0/S1）";
        }
        if (name.contains("old") || name.contains("tenured")) {
            return "堆 · 老年代";
        }
        if (name.equals("metaspace")) {
            return "方法区 · 元空间";
        }
        if (name.contains("compressed class")) {
            return "方法区 · 压缩类空间（元空间的一部分）";
        }
        if (name.startsWith("codeheap") || name.equals("codecache")) {
            // 未开分层编译时只有一个 CodeCache 池，同样属于 JIT 代码缓存
            return "方法区 · JIT 代码缓存";
        }
        return "其他";
    }

    /**
     * 元空间已用 MB。
     *
     * <p>直接从 {@link MemoryPoolMXBean} 取字节再换算，而不是复用已保留两位小数的池视图——
     * 视图值先四舍五入再乘回来，会让同一个数在两张表里差 0.01 MB。</p>
     */
    private static double metaspaceUsedMb() {
        return poolMb("Metaspace", true);
    }

    /**
     * 元空间已提交 MB。committed 与 used 的差就是「类加载后退给 OS 但还没真正还」的部分，
     * 只看 used 会漏掉动态代理把元空间撑大后又卸载掉类的那种情况。
     */
    private static double metaspaceCommittedMb() {
        return poolMb("Metaspace", false);
    }

    /**
     * 按池名取 used 或 committed。
     *
     * <p>找不到池返回 0 而不是抛异常：池的名字在 JDK 版本间改过（如早期的
     * {@code PS Old Gen} / {@code Par Eden Space}），观测接口不该因为换个 JDK 就整个挂掉。</p>
     */
    private static double poolMb(String poolName, boolean used) {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getName().equals(poolName))
                .findFirst()
                // getUsage() 为 null 的池（例如某些 JDK 的 CodeHeap 段）在这里当作 0 处理
                .filter(pool -> pool.getUsage() != null)
                .map(pool -> Units.mb(used ? pool.getUsage().getUsed() : pool.getUsage().getCommitted()))
                .orElse(0.0);
    }

    /** 三段 CodeHeap 合并后的已用 MB：读者关心「JIT 一共占了多少」，而不是每段的零头。 */
    private static double codeHeapUsedMb() {
        return codeHeapMb(true);
    }

    /** 三段 CodeHeap 合并后的已提交 MB，和 {@link #codeHeapUsedMb()} 成对出现。 */
    private static double codeHeapCommittedMb() {
        return codeHeapMb(false);
    }

    private static double codeHeapMb(boolean used) {
        long total = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            // 分层编译开启时代码缓存分三段（CodeHeap 'non-nmethods' / 'profiled nmethods' / 'non-profiled nmethods'）；
            // 被 -XX:TieredStopAtLevel=1 关掉分层后合并成单个 CodeCache 池（spring-boot:run 默认就带这个参数）。
            // 两种命名都必须认，否则代码缓存会被统计成 0 MB。
            if (pool.getName().startsWith("CodeHeap") || pool.getName().equals("CodeCache")) {
                total += used ? pool.getUsage().getUsed() : pool.getUsage().getCommitted();
            }
        }
        return Units.mb(total);
    }

    /**
     * 读取一个大小类参数并换算成 MB。
     *
     * <p>返回 null 表示「没有硬上限」：HotSpot 用 0 表示未设置，
     * 而 0 在读者眼里是「上限 0 MB」，两者必须区分开。</p>
     */
    private Double optionalFlagMb(String name) {
        long value = flags.number(name).orElse(0L);
        return value > 0 ? Units.mb(value) : null;
    }

    /**
     * 按名字取缓冲区池。
     *
     * <p>名字是 JDK 硬编码的小写 {@code direct} 与 {@code mapped}，不是 {@code DirectByteBuffer}
     * 这种类名；写错大小写会得到 null，接口里就显示 0——这是最容易把自己骗过去的假数据，
     * 所以 {@code directMemory()} 里每个取值点都做了 {@code == null ? 0} 的兜底而不是直接解引用。</p>
     */
    private static BufferPoolMXBean bufferPool(String name) {
        return ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                .filter(pool -> pool.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * 读取 NMT 汇总文本；未开启或读取失败时返回空，让上层如实标注 enabled=false。
     *
     * <p>只关心「这一区到底提交了多少」，所以用 summary 而不是 detail：
     * detail 会更准但每次调用都更慢，接口是给读者反复点的使用场景。</p>
     */
    private Optional<String> readNativeMemory() {
        try {
            String text = diagnostics.nativeMemory("summary");
            // 未开启 NMT 时 HotSpot 返回一句提示文本而不是数据
            return text.contains("committed=") ? Optional.of(text) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * NMT 的 {@code Total:} 行。
     *
     * <p>返回的是定长数组而不是记录，因为只有这里用：
     * {@code [0]=reserved MB}、{@code [1]=committed MB}，顺序照抄 NMT 输出里两个字段出现的先后。
     * 找不到 Total 行返回 {@code {0, 0}}——上层已经在 {@code enabled=false} 分支里给出了原因，
     * 这里再猜一个数只会把「读不到」伪装成「读到 0」。</p>
     */
    private static double[] parseTotal(String nmtText) {
        for (String line : nmtText.lines().toList()) {
            Matcher matcher = TOTAL_LINE.matcher(line.trim());
            if (matcher.find()) {
                return new double[]{Units.mb(Long.parseLong(matcher.group(1)) * 1024L),
                        Units.mb(Long.parseLong(matcher.group(2)) * 1024L)};
            }
        }
        return new double[]{0, 0};
    }

    private static Map<String, Double> parseSections(String nmtText) {
        Map<String, Double> committed = new LinkedHashMap<>();
        String currentSection = null;
        for (String raw : nmtText.lines().toList()) {
            String line = raw.strip();
            Matcher name = NMT_SECTION.matcher(line);
            if (name.find()) {
                // 段头形如 "-\tThread\t: reserved=...kB, committed=...kB"；缩进的子行属于上一段
                currentSection = name.group(1).trim();
            }
            Matcher sectionCommitted = COMMITTED.matcher(line);
            if (currentSection != null && NMT_SECTIONS.contains(currentSection) && sectionCommitted.find()) {
                // 同一行既可能是段头也可能是子项，NMT 的口径以 committed 为准，取第一次出现的值
                committed.putIfAbsent(currentSection, Units.mb(Long.parseLong(sectionCommitted.group(1)) * 1024L));
            }
        }
        return committed;
    }

    private static Double percent(long used, long max) {
        return round(used * 100.0 / max);
    }

    /**
     * 统一保留两位小数。
     *
     * <p>MB 全部保留两位是刻意的：直方图、池、NMT 三处都会打印同一份数据，
     * 小数位一致读者才能一眼看出「这两个数是不是同一个东西」；
     * 更重要的是教程里的表格要能对上，第四位小数在虚拟机上没有任何含义。</p>
     */
    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
