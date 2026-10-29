package com.example.demo.jvm.support;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.springframework.stereotype.Component;

/**
 * 读取 JVM 参数（-XX 旗标）的统一入口。
 *
 * <p>为什么需要它：读者在 /api/jvm/areas 里看到「Metaspace 已用 24 MB」时，
 * 第一个问题一定是「上限是多少、谁决定的」。上限只能从 VM 参数里读，
 * 而 MXBean 的 max 字段在未显式设置时会返回 -1（无上限）或 0（走默认值），
 * 语义不一致，容易误读，所以在这里集中翻译一次。</p>
 *
 * <p>实现方式：{@link HotSpotDiagnosticMXBean#getVMOption(String)} 等价于
 * {@code jcmd <pid> VM.flags}，但不需要起子进程，也不依赖外部工具。</p>
 *
 * <p>坑：只有 HotSpot 系（含 Corretto/Zulu/Temurin）有这个 MBean；
 * 换成分支（如 OpenJ9）会抛 IllegalArgumentException，
 * 因此所有读取都做成了「读不到就当未设置」，绝不让观测接口 500。</p>
 */
@Component
public class JvmFlags {

    /**
     * 与运行时数据区一一对应的参数，顺序即接口里展示的顺序。
     * 只列教学会用到的，不要把 VM.flags 全量倒给读者。
     */
    private static final List<String> AREA_FLAGS = List.of(
            "MaxHeapSize",          // -Xmx：堆上限
            "InitialHeapSize",      // -Xms：堆初始值
            "MaxMetaspaceSize",     // 元空间硬上限，未设置=只受物理内存限制
            "MetaspaceSize",        // 首次触发 Full GC 的阈值，注意不是「初始大小」
            "ThreadStackSize",      // -Xss：单线程栈大小，线程数是「栈区」总用量的乘数
            "MaxDirectMemorySize",  // 直接内存上限，0 表示默认与 -Xmx 相同
            "ReservedCodeCacheSize",// JIT 编译代码的缓存上限（属于方法区实现的一部分）
            "SurvivorRatio",        // Eden:Survivor 比例
            "MaxTenuringThreshold", // 对象最大年龄，超过即晋升老年代
            "G1HeapRegionSize",     // G1 分区大小，决定「大对象」门槛
            "ParallelGCThreads",    // 并行 STW 阶段线程数，影响暂停时间与 CPU 争抢
            "MaxGCPauseMillis"      // G1 暂停时间目标，是「目标」不是「保证」
    );

    private final HotSpotDiagnosticMXBean diagnostic =
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);

    /**
     * @return 参数名 -&gt; 原始值（字符串，保持 HotSpot 的写法，布尔旗标为 "true"/"false"）
     *         读不到的参数直接缺席，不会用 null 占位
     */
    public Map<String, String> areaFlags() {
        Map<String, String> flags = new LinkedHashMap<>();
        for (String name : AREA_FLAGS) {
            String value = raw(name);
            if (value != null) {
                flags.put(name, value);
            }
        }
        return flags;
    }

    /**
     * 数值型参数。
     *
     * @param name 参数名，如 {@code ThreadStackSize}
     * @return 参数值；单位取决于参数本身，大小类参数是字节；读不到或不是数字时返回空
     */
    public OptionalLong number(String name) {
        String value = raw(name);
        if (value == null) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            // 少数参数（如 AdaptiveSizePolicy）在这里可能是布尔值，按「未设置」处理即可
            return OptionalLong.empty();
        }
    }

    /**
     * 单线程栈大小，单位字节。
     *
     * <p>虚拟机栈无法通过 MemoryPoolMXBean 观测（它不是 Java 堆里的内存池），
     * 只能用 ThreadStackSize × 活跃线程数估算，或用 NMT 看真实占用。</p>
     *
     * <p>坑：{@code ThreadStackSize} 的原始值单位是 KB（实测 Corretto 21 返回 1024，
     * 也就是默认的 1 MB），直接当字节用会把栈大小算小一千倍。</p>
     */
    public long stackSizeBytes() {
        // 未显式设置 -Xss 时 HotSpot 也会给出一个默认值，所以这里一般不为空
        long sizeKb = number("ThreadStackSize").orElse(1024L);
        return sizeKb * 1024L;
    }

    /**
     * 直接内存上限，单位字节。
     *
     * <p>坑：{@code MaxDirectMemorySize} 默认值是 0，而 0 的含义不是「不能用直接内存」，
     * 而是「按 -Xmx 计算」。这里把 0 翻译回 -Xmx，否则读者会看到上限 0 MB、用量 8 MB 的荒谬结论。</p>
     */
    public long maxDirectMemoryBytes() {
        long configured = number("MaxDirectMemorySize").orElse(0L);
        // 0 或未设置时，JDK 实际用 Runtime.maxMemory() 作为直接内存上限
        return configured > 0 ? configured : Runtime.getRuntime().maxMemory();
    }

    /**
     * 判断是否开启了堆转储与 OOM 自动转储，供文档里的「生产建议」自检使用。
     */
    public boolean heapDumpOnOom() {
        return "true".equalsIgnoreCase(rawOrEmpty("HeapDumpOnOutOfMemoryError"));
    }

    /** 原始参数值，读不到返回 null；调用方自行决定缺省语义。 */
    public String raw(String name) {
        try {
            return diagnostic.getVMOption(name).getValue();
        } catch (Exception e) {
            // 参数不存在、或非 HotSpot VM：观测接口不该因此失败
            return null;
        }
    }

    private String rawOrEmpty(String name) {
        String value = raw(name);
        return value == null ? "" : value;
    }
}
