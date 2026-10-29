package com.example.demo.jvm.oom;

import com.example.demo.jvm.lab.LabArgs;
import com.example.demo.jvm.lab.LabReport;
import com.example.demo.jvm.lab.VmOptions;
import com.example.demo.jvm.support.Units;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 场景3：把「内存溢出」从一个名词变成几种可复现、可辨认的失败。
 *
 * <p>为什么必须分开复现：线上告警只写 {@code OutOfMemoryError}，
 * 但括号里的信息决定了处置方式完全不同——
 * {@code Java heap space} 查大对象和泄漏；{@code Metaspace} 查动态类生成与类加载器泄漏，
 * 而且堆 dump 里看不出任何异常；{@code Direct buffer memory} 查 Netty/MMap，同样不在堆里；
 * {@code unable to create native thread} 根本不是内存不够，而是线程数或系统限制。
 * 把它们混成一句「堆不够」是初学者最常见的弯路。</p>
 *
 * <p>关键实现：每个 case 都是「JVM 参数定上限 → 单调增长占用 → 捕获错误并打印报告」。
 * 上限只能在启动时给（{@code -Xmx} / {@code -XX:MaxMetaspaceSize} / {@code -XX:MaxDirectMemorySize} /
 * {@code -Xss}），所以本类必须由子进程起，见 {@link com.example.demo.jvm.lab.ChildJvm}。</p>
 *
 * <p>坑（都是本机实测踩出来的，按重要性排序）：
 * <ul>
 *   <li><strong>错误处理路径自己也会 OOM</strong>：初版在 {@code catch} 里现读 MXBean，
 *       结果 {@code ClassLoader.loadClass} 申请不到内存，抛出第二个
 *       {@code OutOfMemoryError: Java heap space}，把真正有用的报告整个顶掉。
 *       现在做三件事：{@code catch} 第一句就 {@code retained.clear()}；
 *       报告主体只用「跑的过程中自己累加的计数」，不再依赖新对象；
 *       启动时先空跑一次报告路径（{@link #warmUpReportingPath()}），把要用到的类提前加载完。</li>
 *   <li>直接内存要靠引用留住，否则它一变成垃圾就被 {@code Cleaner} 回收，
 *       实验表现为「怎么分配都不溢出」；</li>
 *   <li>Metaspace 用同一个 ClassLoader 反复定义同名类会直接抛 {@code LinkageError}，
 *       必须每次新建 ClassLoader；</li>
 *   <li>{@code StackOverflowError} 不是 {@code OutOfMemoryError}（两者都只是 {@code VirtualMachineError}
 *       的兄弟），所以这里按 {@code Throwable} 捕获，报告里如实区分；</li>
 *   <li>{@code gcOverhead} 和 {@code thread} 在本机不一定给出预期的那句话：
 *       实测 {@code gcOverhead}（{@code -Xmx32m -XX:+UseSerialGC -XX:+UseGCOverheadLimit}）
 *       抛的是 {@code Java heap space}，{@code thread}（2000 个线程）干脆不炸。
 *       所以报告里 {@code reproduced}（炸没炸）、{@code expectedError}（本该炸出哪句）、
 *       {@code asExpected}（炸对了没）是三个独立字段——
 *       复现不了是结论，不是失败，文档只写实测到的那部分。</li>
 * </ul></p>
 */
public final class OomLab {

    /** 保留引用，防止被回收；不同 case 往里塞不同类型的东西。 */
    private final List<Object> retained = new ArrayList<>();

    /** 进度计数：溢出前跑了多少轮，用来判断是「一下就炸」还是「慢慢涨到炸」。 */
    private long iterations;

    /**
     * 自己累加的分配量，单位字节。
     *
     * <p>为什么不留到报告里用 MXBean 现读：溢出之后再申请内存去问「你用了多少」是不可靠的
     * （见类注释第一条坑），而循环里给一个 long 字段 {@code +=} 不产生任何新对象。</p>
     */
    private long allocatedBytes;

    /**
     * 复现一种 OOM。
     *
     * <p>用法：{@code java -Xmx64m -cp demo-jvm/target/classes
     * com.example.demo.jvm.oom.OomLab --case=heap --chunkMb=8}。
     * {@code --case} 可选 {@code heap / stack / metaspace / direct / gcOverhead / thread}，
     * 写错会直接把可选值打印出来——这六个案例各自需要的启动参数不一样
     * （{@code -Xmx}、{@code -XX:MaxMetaspaceSize}、{@code -XX:MaxDirectMemorySize}、{@code -Xss}），
     * 所以本类必须从外面起进程，见类注释。</p>
     *
     * <p>{@code --case=gcOverhead} 是故意留的反例：本机 {@code -Xmx64m} 下跑了 6 万次分配，
     * 先撞上的是 {@code errorMessage=Java heap space} 而不是期望的那条，
     * 于是报告里 {@code reproduced=true} 但 {@code asExpected=false}。
     * 这不是实验失败，而是「GC overhead limit exceeded 在 JDK 8 之后很难被稳定触发」的实测证据。</p>
     */
    public static void main(String[] args) {
        LabArgs parsed = LabArgs.parse(args);
        String caseName = parsed.string("case", "heap");
        OomLab lab = new OomLab();
        lab.warmUpReportingPath();
        try {
            lab.run(caseName, parsed);
            // 跑完还没溢出：这本身是一种结论，必须如实报告而不是假装成功
            lab.report(caseName, null).print();
        } catch (Throwable thrown) {
            // 顺序不能调：先把引用全部放掉，才有空间做后面所有会分配对象的事
            lab.retained.clear();
            lab.report(caseName, thrown).print();
        }
    }

    /**
     * 分发到具体 case。
     *
     * @param caseName {@code heap} / {@code stack} / {@code metaspace} / {@code direct} /
     *                 {@code gcOverhead} / {@code thread}
     * @param parsed   实验参数，各 case 取自己需要的
     */
    private void run(String caseName, LabArgs parsed) throws IOException {
        switch (caseName) {
            case "heap" -> heap(parsed.number("chunkMb", 8));
            case "stack" -> stack();
            case "metaspace" -> metaspace();
            case "direct" -> direct(parsed.number("chunkMb", 16));
            case "gcOverhead" -> gcOverhead();
            case "thread" -> threads(parsed.number("maxThreads", 2_000),
                    parsed.number("threadStackMb", 64));
            default -> throw new IllegalArgumentException("未知 --case=" + caseName
                    + "，可选：heap / stack / metaspace / direct / gcOverhead / thread");
        }
    }

    /** 堆：每次留 {@code chunkMb} MB 在集合里，只进不出，直到 {@code Java heap space}。 */
    private void heap(int chunkMb) {
        while (true) {
            byte[] block = new byte[chunkMb * 1024 * 1024];
            // 首尾各写一个字节：让这段内存的首尾页真正被摸到，
            // 否则操作系统层面可能仍是零页，用量看起来涨得比实际慢
            block[0] = 1;
            block[block.length - 1] = 1;
            retained.add(block);
            allocatedBytes += block.length;
            iterations++;
        }
    }

    /** 栈：无终止递归，压到 {@code StackOverflowError}（注意它不是 OutOfMemoryError）。 */
    private void stack() {
        recurse();
    }

    private void recurse() {
        iterations++;
        // 一个局部变量，保证这一帧真实存在且不能被优化成跳转（Java 没有尾调用优化，但别赌）
        long frameMarker = iterations * 31;
        if (frameMarker < 0) {
            return;
        }
        recurse();
    }

    /**
     * 元空间：每次用一个全新的 ClassLoader 定义同一个接口的副本，并留住引用。
     *
     * <p>为什么这样能涨 Metaspace：类的元数据（klass 结构、常量池、方法表）存在元空间，
     * 且<strong>按类加载器隔离</strong>。同一个 ClassLoader 加载同名类只能一次，
     * 但 N 个 ClassLoader 各自加载一份就是 N 个不同的类。真实故障里最常见的那个泄漏模式
     * ——动态代理、Groovy 脚本、CGLIB——本质都是「不断产生新类」。</p>
     *
     * <p>坑：只要 ClassLoader 可达，它定义的类就不会被卸载；
     * 所以必须把 ClassLoader 和 Class 都放进 {@code retained}，否则一 Full GC 就全还回去了。</p>
     */
    private void metaspace() throws IOException {
        byte[] interfaceBytes = classBytesOf(Leaky.class);
        while (true) {
            OnlyOnce loader = new OnlyOnce(getClass().getClassLoader());
            Class<?> copy = loader.define(interfaceBytes);
            retained.add(loader);
            retained.add(copy);
            // 元空间的增量至少是这份字节码；klass 结构等额外开销不猜，交给 probe 里读到的实测值
            allocatedBytes += interfaceBytes.length;
            iterations++;
        }
    }

    /** 从一个 ClassLoader 里读出某个类的字节码，供复制到新 ClassLoader 用。 */
    private static byte[] classBytesOf(Class<?> target) throws IOException {
        String resource = target.getName().replace('.', '/') + ".class";
        try (InputStream in = target.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("找不到 " + resource + "，先执行 ./mvnw -pl demo-jvm compile");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            return buffer.toByteArray();
        }
    }

    /** 只允许定义一个类的 ClassLoader，用来制造「每个加载器一份类元数据」。 */
    private static final class OnlyOnce extends ClassLoader {
        OnlyOnce(ClassLoader parent) {
            // parent 必须保留：类的解析需要父加载器能找到被引用的类型
            super(parent);
        }

        Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }

    /**
     * 标记类：只是给 {@link #metaspace()} 一份可以反复定义的合法字节码。
     *
     * <p>它刻意是个接口——接口字节码最小，且不依赖任何业务类型，
     * 复制到新 ClassLoader 时不会因为缺少依赖而解析失败。</p>
     */
    public interface Leaky {
    }

    /**
     * 直接内存：堆外分配且留住引用，直到 {@code Direct buffer memory}。
     *
     * <p>坑：如果只 {@code allocateDirect} 而不保存返回的 {@code ByteBuffer}，
     * 它一变成垃圾就会被 {@code Cleaner} 立刻释放，实验表现为「无限分配也不溢出」。
     * 这正是直接内存最难查的地方——它不在堆 dump 里。</p>
     */
    private void direct(int chunkMb) {
        while (true) {
            ByteBuffer buffer = ByteBuffer.allocateDirect(chunkMb * 1024 * 1024);
            // 写一个字节：某些平台真正映射地址是在首次访问时，不写可能看不到 RSS 变化
            buffer.put(0, (byte) 1);
            retained.add(buffer);
            allocatedBytes += buffer.capacity();
            iterations++;
        }
    }

    /**
     * GC overhead limit：小对象全部留住，让收集器反复空转。
     *
     * <p>想复现的是这条判定：<strong>绝大部分时间在 GC，且每次回收不到 {@code -XX:GCHeapFreeRate}
     * 的比例</strong>（默认 1%）。触发条件是「活的对象几乎占满堆，但没有一个大对象」——
     * 所以用 1 KB 小数组，而不是像 {@link #heap(int)} 那样一次几十 MB
     * （那种情况会先炸出 {@code Java heap space}）。</p>
     *
     * <p>实测结论（Corretto 21）：{@code -Xmx32m/-Xmx64m/-Xmx128m/-Xmx256m} 配
     * Serial / Parallel / G1 都没触发那句 {@code GC overhead limit exceeded}，
     * 一律是 {@code Java heap space}。所以这个 case 留在代码里的作用是反例：
     * <strong>别指望用错误消息区分「泄漏」和「堆小」</strong>，要靠 GC 日志和 dump 对比。</p>
     */
    private void gcOverhead() {
        while (true) {
            byte[] tiny = new byte[1024];
            // 1 KB × 几万个：Old 区被填满，但每次 Full GC 几乎清不出东西
            retained.add(tiny);
            allocatedBytes += tiny.length;
            iterations++;
        }
    }

    /**
     * 线程数上限：{@code unable to create native thread}。
     *
     * <p>想纠正的误解：这句话前面挂的是 {@code java.lang.OutOfMemoryError}，
     * 很多人以为是 Java 堆不够，实际是「每个线程要一段栈加一份 OS 线程」，
     * 撞到的是系统级限制（{@code ulimit -u}、cgroup pids.max、线程栈总预算）。</p>
     *
     * <p>两个可调旋钮，因为限制可能来自两个方向：
     * {@code --threadStackMb} 把单个线程的栈放大（撞地址空间/内存预算），
     * {@code --maxThreads} 把线程数放大（撞 OS 的线程/进程计数）。
     * 传 0 给 {@code threadStackMb} 表示「用 {@code -Xss} 的默认值」。</p>
     *
     * <p>实测结论（macOS x64，16 GB 内存，{@code ulimit -u}=2784）：
     * 2000 个 64 MB 栈的线程照样起得来——虚拟内存超配，pthread 只是保留地址空间，
     * 不写就不占物理页。所以这个 case 在开发机上要拧到几千线程以上才可能触限，
     * 生产上更常见的是 Linux 容器里 {@code pids.max} 或 {@code ulimit -u} 先挡住。
     * {@code maxThreads} 本身就是保护开发机的上限。</p>
     *
     * @param maxThreads   最多起多少个线程，超过就正常结束（报告里 {@code errorClass=none}）
     * @param stackMb      单线程栈大小，MB；0 表示沿用 {@code -Xss}
     */
    private void threads(int maxThreads, int stackMb) {
        // 传给 Thread 构造函数的 0 表示「用 JVM 默认栈」，必须保留；
        // 但记账时不能记 0：否则报告里 selfCountedAllocMb=0.0，读者会以为线程不占内存，
        // 而实际上每个线程照样占一份 -Xss 的栈——这正是本 case 想说明的东西
        long requestedStackBytes = stackMb <= 0 ? 0L : stackMb * 1024L * 1024L;
        long countedStackBytes = stackMb <= 0 ? defaultStackBytes() : requestedStackBytes;
        while (iterations < maxThreads) {
            Thread worker = new Thread(null, () -> {
                try {
                    Thread.sleep(30_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "oom-thread-" + iterations, requestedStackBytes);
            worker.setDaemon(true);
            worker.start();
            retained.add(worker);
            // 这里累加的是「保留」的栈空间，不是物理占用：报告里 selfCountedAllocMb
            // 会因此远大于堆上限（实测 2000×64MB=128000 MB），这正是「保留≠占用」的证据
            allocatedBytes += countedStackBytes;
            iterations++;
        }
    }

    /**
     * 当前进程的默认线程栈大小，单位字节。
     *
     * <p>{@code ThreadStackSize} 的原始值是 KB（实测 Corretto 21 默认 1024，即 1 MB），
     * 直接当字节用会把栈算小一千倍。</p>
     */
    private static long defaultStackBytes() {
        Long kb = VmOptions.number("ThreadStackSize");
        // 读不到时按 JVM 规范的 1 MB 兜底，别让记账变成 0
        return (kb == null || kb <= 0 ? 1024L : kb) * 1024L;
    }

    /**
     * 汇总报告。
     *
     * @param caseName 哪个 case
     * @param thrown   抛出的错误；{@code null} 表示跑到上限也没炸
     */
    private LabReport report(String caseName, Throwable thrown) {
        String errorClass = thrown == null ? "none" : thrown.getClass().getName();
        String errorMessage = messageOf(thrown);
        Expectation expected = expectation(caseName);
        LabReport report = LabReport.of("oom")
                .put("case", caseName)
                .put("errorClass", errorClass)
                // reproduced 只回答「炸没炸」；asExpected 才回答「炸的是不是预期那一句」。
                // 少了这个区分，gcOverhead 这种「炸了但炸成 Java heap space」会被读成复现成功。
                .put("reproduced", thrown != null)
                .put("expectedError", expected.describe())
                .put("asExpected", expected.matches(errorClass, errorMessage))
                // 注意 StackOverflowError 的 getMessage() 是 null，直接 String.valueOf 会打印出
                // 字面量 "null"，初学者会以为是程序 bug，所以显式写成 "(无消息)"。
                .put("errorMessage", errorMessage)
                .put("topFrame", thrown == null ? "" : topFrameOf(thrown))
                .put("iterationsBeforeFailure", iterations)
                .putMb("selfCountedAllocMb", allocatedBytes / (1024.0 * 1024.0))
                // 四个上限对应四个 case，一眼看出是谁的规则让进程炸的
                .put("flag.MaxHeapSizeMb", Units.mb(Runtime.getRuntime().maxMemory()))
                .put("flag.MaxMetaspaceSize", VmOptions.describeBytes("MaxMetaspaceSize"))
                .put("flag.MaxDirectMemorySize", VmOptions.describeBytes("MaxDirectMemorySize"))
                .put("flag.ThreadStackSizeKb", VmOptions.number("ThreadStackSize"))
                .put("threadCountPeak", peakThreads());
        return probeSafely(report);
    }

    /**
     * 错误消息，缺省时写清楚「就是没有」而不是留一个容易误读的占位。
     *
     * <p>{@code OutOfMemoryError} 一定有消息（{@code Java heap space} / {@code Metaspace} /
     * {@code Direct buffer memory}……），这句话就是分诊依据；
     * {@code StackOverflowError} 一定没有消息，所以 {@code (无消息)} 本身也是信息。</p>
     */
    private static String messageOf(Throwable thrown) {
        if (thrown == null) {
            return "(未溢出)";
        }
        String message = thrown.getMessage();
        return message == null || message.isBlank() ? "(无消息)" : message;
    }

    /**
     * 每个 case 期望看到的错误，用来把「炸了」和「炸对了」分开。
     *
     * @param errorClass     期望的异常类全名
     * @param messageFragment 期望消息里出现的关键词；{@code null} 表示不看消息
     */
    private record Expectation(String errorClass, String messageFragment) {

        /** 匹配判定；消息用不区分大小写的包含，因为不同 JDK 的措辞大小写不一致（实测 direct 是小写开头）。 */
        boolean matches(String actualClass, String actualMessage) {
            if (!errorClass.equals(actualClass)) {
                return false;
            }
            return messageFragment == null
                    || actualMessage.toLowerCase(java.util.Locale.ROOT)
                            .contains(messageFragment.toLowerCase(java.util.Locale.ROOT));
        }

        /** 报告里的一行说明，让读者不用翻源码也知道「应该看到什么」。 */
        String describe() {
            return messageFragment == null ? errorClass : errorClass + ": " + messageFragment;
        }
    }

    /** 按 case 取期望值。这里的每一条都来自本机实测或 JDK 源码里的固定措辞，不是猜的。 */
    private static Expectation expectation(String caseName) {
        return switch (caseName) {
            case "heap" -> new Expectation("java.lang.OutOfMemoryError", "Java heap space");
            // 栈溢出不是 OutOfMemoryError，所以连类名一起断言
            case "stack" -> new Expectation("java.lang.StackOverflowError", null);
            case "metaspace" -> new Expectation("java.lang.OutOfMemoryError", "Metaspace");
            case "direct" -> new Expectation("java.lang.OutOfMemoryError", "direct buffer memory");
            case "gcOverhead" -> new Expectation("java.lang.OutOfMemoryError", "GC overhead limit exceeded");
            case "thread" -> new Expectation("java.lang.OutOfMemoryError", "unable to create native thread");
            default -> new Expectation("", null);
        };
    }

    /**
     * 溢出之后再问 MXBean：能读到多少算多少，读不到就把「读不到」写进报告，绝不抛。
     *
     * <p>这一层存在的唯一理由：{@code heap} case 触发时堆是真的满了，
     * 任何「顺手读一下指标」的代码都可能把整个报告带崩（实测过）。
     * 生产代码里做 OOM 兜底日志，也必须按这个思路——先放手、优先用已有计数、
     * 外部查询全部包起来。</p>
     *
     * <p>坑（读这几个 {@code probe.*} 时必须记住）：它们是在 {@code retained.clear()} <em>之后</em>
     * 采样的，所以反映的是「放手之后还剩多少」，不是溢出那一刻的水位。
     * 实测 {@code metaspace} case 放手后 GC 顺手卸载了类，{@code probe.metaspaceUsedAfterReleaseMb}
     * 只剩 7 MB 出头，而真正把上限顶满是放手前的几十 MB——
     * 想知道溢出瞬间的占用，看 {@code selfCountedAllocMb} 或 GC 日志，别看这里。</p>
     */
    private LabReport probeSafely(LabReport report) {
        try {
            report.putMb("probe.heapUsedAfterReleaseMb",
                    Units.mb(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()));
        } catch (Throwable unable) {
            report.put("probe.heapUsedAfterReleaseMb", "(读不到: " + unable.getClass().getSimpleName() + ")");
        }
        try {
            long directUsed = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class).stream()
                    .filter(pool -> "direct".equals(pool.getName()))
                    .mapToLong(BufferPoolMXBean::getMemoryUsed)
                    .sum();
            report.putMb("probe.directUsedAfterReleaseMb", Units.mb(directUsed));
        } catch (Throwable unable) {
            report.put("probe.directUsedAfterReleaseMb", "(读不到: " + unable.getClass().getSimpleName() + ")");
        }
        try {
            // 类的元数据其实主要落在 Compressed Class Space（压缩指针开启时），
            // 所以两个都读，只报 Metaspace 会让人以为「没用多少啊」
            report.putMb("probe.metaspaceUsedAfterReleaseMb", poolUsedMb("Metaspace"));
            report.putMb("probe.compressedClassUsedAfterReleaseMb", poolUsedMb("Compressed Class Space"));
            report.put("pool.MetaspaceMax", poolMaxDesc("Metaspace"));
            report.put("pool.CompressedClassSpaceMax", poolMaxDesc("Compressed Class Space"));
        } catch (Throwable unable) {
            report.put("probe.metaspaceUsedAfterReleaseMb", "(读不到: " + unable.getClass().getSimpleName() + ")");
        }
        return report;
    }

    /**
     * 启动时把报告路径先走一遍。
     *
     * <p>目的不是拿数据，而是让 {@code LabReport}、{@code Units}、{@code ManagementFactory}
     * 相关的那些类<strong>在还有内存的时候</strong>就加载完——否则溢出时 {@code loadClass}
     * 要再申请堆内存，实测就是这么抛出第二个 OOM 把真实报告顶掉的。</p>
     */
    private void warmUpReportingPath() {
        LabReport scratch = LabReport.of("oom-warmup");
        probeSafely(scratch);
        VmOptions.describeBytes("MaxMetaspaceSize");
        peakThreads();
        topFrameOf(new IllegalStateException("warm up"));
        // 整条 report() 也走一遍（不 print）：把 Expectation、messageOf、字符串拼接这些
        // 只在异常路径上第一次碰到的类提前加载，否则真正溢出时才去 loadClass
        report("warmup", new IllegalStateException("warm up"));
    }

    /** 某个内存池的已用量，MB。 */
    private static double poolUsedMb(String poolName) {
        return poolOf(poolName).map(pool -> Units.mb(pool.getUsage().getUsed())).orElse(0.0);
    }

    /**
     * 某个内存池的上限，写成人能看懂的一句话。
     *
     * <p>坑：元空间没设 {@code -XX:MaxMetaspaceSize} 时，{@code MemoryPoolMXBean} 的 max 是 <strong>-1</strong>
     * （压缩类空间则始终有预留值）。直接把 -1 换算成 MB 会四舍五入成 {@code 0.0}，
     * 于是报告里出现「上限 0 MB、用量 30 MB」这种看着像 bug 的行。
     * 所以这里显式翻译：负数一律写「不限」。</p>
     */
    private static String poolMaxDesc(String poolName) {
        return poolOf(poolName)
                .map(pool -> {
                    long max = pool.getUsage().getMax();
                    return max < 0 ? "-1(不限，只受物理内存限制)" : Units.mb(max) + " MB";
                })
                .orElse("(池不存在)");
    }

    private static Optional<MemoryPoolMXBean> poolOf(String poolName) {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(pool -> pool.getName().equals(poolName))
                .findFirst();
    }

    private static int peakThreads() {
        return ManagementFactory.getThreadMXBean().getPeakThreadCount();
    }

    /**
     * 取栈顶帧，失败也要返回字符串而不是抛异常。
     *
     * <p>坑：{@code getStackTrace()} 本身要分配对象，堆满时它会再抛 OOM；
     * 栈溢出时它又可能返回空数组。两种情况都只能吞掉——
     * 报告剩下的字段（错误消息）比栈顶帧重要得多。</p>
     */
    private static String topFrameOf(Throwable thrown) {
        try {
            StackTraceElement[] frames = thrown.getStackTrace();
            return frames.length == 0 ? "(no stack)" : frames[0].toString();
        } catch (Throwable unable) {
            return "(栈信息不可用: " + unable.getClass().getSimpleName() + ")";
        }
    }

    private OomLab() {
    }
}
