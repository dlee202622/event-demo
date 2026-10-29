package com.example.demo.jvm.gc;

import com.example.demo.jvm.lab.LabArgs;
import com.example.demo.jvm.lab.LabReport;
import com.example.demo.jvm.support.Units;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

/**
 * 场景4 的子进程入口：用「当前这套 JVM 参数」跑一遍固定负载，把可比较的数字打成 key=value。
 *
 * <p>为什么拆成「子进程跑负载 + 父进程解析日志」两件事：
 * GC 参数是进程级的，同一个 JVM 没法既是 Serial 又是 ZGC；而 {@code -Xlog:gc*} 的日志
 * 要等进程写完才好读，让子进程读自己的日志会踩到「最后一行还没落盘」的边界。
 * 所以这里只负责<strong>干活并汇报自己知道的</strong>（耗时、分配量、校验和、进程内 GC 计数），
 * 停顿明细交给 {@link GcCompareLab} 用 {@link GcLogParser} 解析，避免同一个指标出现两个来源。</p>
 *
 * <p>单独跑它也有意义：把 {@code -Xlog:gc*:stdout} 带上，就能直观看到某个收集器的原始日志长什么样。</p>
 *
 * <p>输出里的 {@code gc.beans} 是防呆字段：它列出进程实际注册的收集器 Bean。
 * 如果把 {@code -XX:+UseG1GC} 拼错成别的名字，JVM 不会报错而是回落到默认收集器，
 * 靠这行能立刻发现「参数没生效」。</p>
 *
 * <p>坑：本类不带任何第三方依赖（{@code -cp target/classes} 里没有 Jackson），
 * 所以输出必须是纯文本，这也是 {@link LabReport} 用 key=value 的原因。</p>
 */
public final class GcLab {

    /**
     * 在当前进程的收集器与堆参数下跑一份确定性的分配负载，把结果打成 key=value。
     *
     * <p>用法：{@code java -Xms128m -Xmx128m -XX:+UseSerialGC -Xlog:gc*:file=gc.log
     * -cp demo-jvm/target/classes com.example.demo.jvm.gc.GcLab
     * --collector=serial --rounds=40 --churnMb=16 --liveMb=8 --objectKb=32 --seed=42}。
     * 收集器、堆大小都<strong>只能从命令行给</strong>，这里没有对应的参数——
     * 这是有意的设计，见类注释里「为什么拆成子进程 + 父进程解析」。</p>
     *
     * <p>{@code --seed} 决定校验和：同一个 seed 在任何收集器、任何机器上算出的
     * {@code checksum} 都相同，因此它同时是「负载确实一样」的凭据。</p>
     */
    public static void main(String[] args) {
        LabArgs parsed = LabArgs.parse(args);
        String collector = parsed.string("collector", "default");
        int rounds = parsed.number("rounds", 40);
        int churnMb = parsed.number("churnMb", 16);
        int liveMb = parsed.number("liveMb", 8);
        int objectKb = parsed.number("objectKb", 32);
        long seed = parsed.number("seed", 42);

        AllocationWorkload.Stats stats = AllocationWorkload.run(rounds, churnMb, liveMb, objectKb, seed);

        LabReport.of("gc-workload")
                .put("collector", collector)
                // 进程实际注册的收集器：参数拼错时会看到默认收集器的名字而不是期望的那个
                .put("gc.beans", collectorBeanNames())
                .put("heap.xmxMb", Units.mb(Runtime.getRuntime().maxMemory()))
                .put("rounds", rounds)
                .put("churnMb", churnMb)
                .put("liveMb", liveMb)
                .put("objectKb", objectKb)
                .put("seed", seed)
                .putMs("workload.wallMs", stats.wallClockMs())
                .putMb("workload.allocatedMb", stats.allocatedMb())
                .put("workload.objects", stats.objects())
                // 校验和跨收集器必须相等，否则说明两次跑的并不是同一份工作
                .put("workload.checksum", stats.checksum())
                .put("gc.countInProcess", stats.gcCount())
                .putMs("gc.timeInProcessMs", stats.gcTimeMs())
                .putMb("heap.usedStartMb", stats.heapUsedStartMb())
                .putMb("heap.usedEndMb", stats.heapUsedEndMb())
                .print();
    }

    /**
     * 当前进程注册的收集器 Bean 名，用竖线连接，供 {@code gc.beans} 字段防呆。
     *
     * <p>用普通循环而不是 stream：这里只是拼字符串，循环里加注释更省事。</p>
     */
    private static String collectorBeanNames() {
        StringBuilder joined = new StringBuilder();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (joined.length() > 0) {
                joined.append('|');
            }
            joined.append(bean.getName());
        }
        return joined.toString();
    }

    private GcLab() {
    }
}
