package com.example.demo.jvm.support;

import java.lang.management.ManagementFactory;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import org.springframework.stereotype.Component;

/**
 * 在进程内调用 HotSpot 诊断命令，等价于在外面敲 {@code jcmd <pid> XXX}。
 *
 * <p>为什么不用 ProcessBuilder 起 jcmd：jcmd 会走 attach 机制动态加载 agent jar，
 * JDK 21 默认会打印一条安全告警，而且要求 jcmd 可执行文件在同一路径下。
 * 直接调用平台 MBean {@code com.sun.management:type=DiagnosticCommand}
 * 拿到的是完全相同的输出，却没有外部依赖，因此观测接口用它，
 * 文档里再给出等价的 jcmd 命令，让读者在容器/线上也能照做。</p>
 *
 * <p>命令对照（方法名 -&gt; jcmd 命令）：
 * heapInfo -&gt; {@code GC.heap_info}，classHistogram -&gt; {@code GC.class_histogram}，
 * nativeMemory -&gt; {@code VM.native_memory}，threadDump -&gt; {@code Thread.print}，
 * gcRun -&gt; {@code GC.run}，allFlags -&gt; {@code VM.flags}，
 * metaspaceInfo -&gt; {@code VM.metaspace}，classLoaderStats -&gt; {@code VM.classloader_stats}。</p>
 *
 * <p>耗时：这些都是 STW 或全堆扫描类命令，在几百 MB 堆上 {@code GC.class_histogram}
 * 可能耗时 1 秒以上；接口默认参数只用于观测本模块自身的小堆，不会长时间阻塞。
 * 不要在承载流量的进程上频繁调用 {@code GC.run}。</p>
 *
 * <p>坑：{@code VM.native_memory} 需要进程以 {@code -XX:NativeMemoryTracking=summary|detail}
 * 启动，否则抛异常；本模块的 spring-boot 插件已默认加上，用 {@code java -jar} 直接起时要自己带。</p>
 */
@Component
public class DiagnosticCommands {

    /** 无参命令的 signature 空数组。 */
    private static final String[] NO_ARGS = new String[0];
    /** HotSpot 的诊断命令统一接收一个字符串数组参数，JMX 里它的类型名就是这个。 */
    private static final String[] STRING_ARRAY_TYPE = {String[].class.getName()};

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private final ObjectName objectName = objectName();

    /** 堆的分代明细：各代 used/committed、region 数量、Metaspace 与 class space。 */
    public String heapInfo() {
        return invoke("gcHeapInfo");
    }

    /**
     * 类直方图，按实例字节数倒序。
     *
     * @param live true 时只统计存活对象（会先触发一次 Full GC），排查内存泄漏用 live；
     *             false 时包含尚未回收的垃圾，用来观察「分配得很凶但其实是垃圾」
     * @return jcmd 原始文本，首行是表头
     */
    public String classHistogram(boolean live) {
        // 坑（实测）：GC.class_histogram 并没有 -live 这个选项，只有 -all 与 -parallel，
        // 传 -live 会直接抛 "Unknown argument '-live' in diagnostic command."。
        // jmap -histo:live 的「live」是靠先做一次 Full GC 实现的，所以这里照做：
        // 先 GC.run，再用默认（all=false，只遍历可达对象）出直方图。
        if (live) {
            gcRun();
            return invoke("gcClassHistogram", new String[0]);
        }
        // all=true：把还没回收的垃圾也算进来，用来看「分配得凶」和「真的留着」的区别
        return invoke("gcClassHistogram", "-all");
    }

    /** 线程栈、代码缓存、GC 结构等 JVM 自己占用的本地内存，需要启动时开启 NMT。 */
    public String nativeMemory(String level) {
        try {
            return invoke("vmNativeMemory", level);
        } catch (RuntimeException e) {
            // NMT 未开启是「用法不对」而不是「服务坏了」，转成 400 并把补救参数写进消息
            throw new IllegalArgumentException(
                    "VM.native_memory 需要进程以 -XX:NativeMemoryTracking=" + level
                            + " 启动（当前未开启）。原始信息：" + rootMessage(e), e);
        }
    }

    /** 全量 VM 参数，包含未显式设置的默认值，比 -XX 命令行更能看出真实配置。 */
    public String allFlags() {
        return invoke("vmFlags", "-all");
    }

    /** 元空间明细：chunk 分布、各 class loader 占用，用来解释「Metaspace 涨了但不知道谁占的」。 */
    public String metaspaceInfo(String... arguments) {
        return invoke("vmMetaspace", arguments);
    }

    /** 类加载器统计，配合 Metaspace OOM 实验看「到底创建了多少个 loader」。 */
    public String classLoaderStats() {
        return invoke("vmClassloaderStats");
    }

    /** 全线程栈快照，等价于 {@code Thread.print}；排查死锁和栈深度时用它。 */
    public String threadDump() {
        // Thread.print 在 MBean 里是带 String[] 参数的命令（可接 -l / -e），
        // 即使不传选项也必须走数组签名，否则会报 mismatched signature
        return invoke("threadPrint", new String[0]);
    }

    /** 死锁检测结果，MXBean 直接给出，不需要解析栈文本。 */
    public String deadlocks() {
        long[] ids = java.lang.management.ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        if (ids == null) {
            return "No deadlocks found.";
        }
        StringBuilder text = new StringBuilder("Found ").append(ids.length).append(" deadlocked threads:\n");
        for (var info : java.lang.management.ManagementFactory.getThreadMXBean().getThreadInfo(ids, 8)) {
            if (info != null) {
                text.append("  ").append(info.getThreadName())
                        .append(" waiting on ").append(info.getLockName()).append('\n');
            }
        }
        return text.toString();
    }

    /** 主动触发一次 Full GC（{@code GC.run}），用来把「GC 次数增加了」变成可观测的前后对比。 */
    public String gcRun() {
        return invoke("gcRun");
    }

    /** JVM 版本与构建信息。 */
    public String vmVersion() {
        return invoke("vmVersion");
    }

    /**
     * 调用无参命令（{@code GC.heap_info}、{@code GC.run} 等，MBean 里签名是空数组）。
     */
    public String invoke(String operation) {
        return call(operation, NO_ARGS, NO_ARGS);
    }

    /**
     * 调用带参命令。
     *
     * <p>HotSpot 把这些命令的参数统一声明成 {@code String[]}，
     * 所以哪怕一个参数都不传（例如 {@code VM.metaspace}），也必须走这个入口，
     * 用无参入口会报「命令签名不匹配」。</p>
     */
    public String invoke(String operation, String... arguments) {
        return call(operation, new Object[]{arguments}, STRING_ARRAY_TYPE);
    }

    private String call(String operation, Object[] params, String[] signature) {
        try {
            Object result = server.invoke(objectName, operation, params, signature);
            return result == null ? "" : result.toString().trim();
        } catch (Exception e) {
            // 命令不存在 / 参数非法 / 底层 IO 失败，统一带上原始消息，方便读者判断是拼错还是环境缺能力
            throw new IllegalStateException("诊断命令 " + operation + " 执行失败：" + rootMessage(e), e);
        }
    }

    private static ObjectName objectName() {
        try {
            return new ObjectName("com.sun.management:type=DiagnosticCommand");
        } catch (Exception e) {
            throw new IllegalStateException("当前 JVM 不支持 DiagnosticCommand MBean", e);
        }
    }

    /** JMX 会把真实异常包成 InvocationTargetException，取最里面的消息才有诊断价值。 */
    private static String rootMessage(Throwable error) {
        Throwable cursor = error;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? cursor.toString() : cursor.getMessage();
    }
}
