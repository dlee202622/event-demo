package com.example.demo.jvm.gc;

import com.example.demo.jvm.lab.ChildJvm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 4 的可比性测试：换收集器只能换回收策略，不能换工作量。
 *
 * <p>{@link GcCompareLab} 的整张表都靠一个前提——Serial / Parallel / G1 / ZGC 跑的是同一份工作。
 * 这里用校验和把它钉住：同一参数、同一 seed 下，{@code workload.checksum} 必须逐字符相等。
 * 一旦有人改了负载却没改 seed，让「ZGC 停顿更短」其实是「ZGC 那一轮干的活更少」，这条测试会红。</p>
 *
 * <p>为什么不在测试里断言停顿时间：那是 timings，受机器负载影响，本机两次跑
 * Serial 的 {@code totalMs} 就能从 36 ms 跳到 48 ms。教学文档里用区间表达，测试里干脆不碰。</p>
 */
class GcLabTest {

    /** 用固定小组合跑一个收集器，10 轮 × 8 MB ≈ 84 MB 分配，一秒内结束。 */
    private static Map<String, String> run(String collectorLabel, List<String> gcFlags) {
        List<String> flags = new java.util.ArrayList<>(List.of("-Xms128m", "-Xmx128m"));
        flags.addAll(gcFlags);
        ChildJvm.Result result = ChildJvm.run(flags, GcLab.class.getName(),
                List.of("--collector=" + collectorLabel, "--rounds=10", "--churnMb=8", "--liveMb=4"), 90_000);
        Map<String, String> report = result.report();
        assertNotNull(report.get("workload.checksum"),
                collectorLabel + " 没吐出报告，退出码=" + result.exitCode() + " stderr=" + result.stderr());
        return report;
    }

    @Test
    @DisplayName("Serial 与 G1 的校验和、对象数、分配量完全一致")
    void sameWorkloadAcrossCollectors() {
        Map<String, String> serial = run("Serial", List.of("-XX:+UseSerialGC"));
        Map<String, String> g1 = run("G1", List.of("-XX:+UseG1GC"));

        assertEquals(serial.get("workload.checksum"), g1.get("workload.checksum"));
        // 本机 rounds=10 churn=8 live=4 objectKb=32 seed=42 的校验和是 -4141。
        // 它是纯算术结果（LCG 填充的字节求和），与 JVM、收集器、机器都无关，
        // 所以可以写死；写死之后它同时兼任「负载定义没被人顺手改过」的守卫
        assertEquals("-4141", serial.get("workload.checksum"),
                "校验和变了说明负载或 seed 被动过，文档里的对比数字全部作废");
        assertEquals(serial.get("workload.objects"), g1.get("workload.objects"));
        assertEquals(serial.get("workload.allocatedMb"), g1.get("workload.allocatedMb"));
    }

    @Test
    @DisplayName("gc.beans 读的是进程真实注册的收集器，而 bean 名和参数名根本不是一套")
    void reportsTheCollectorActuallyInUse() {
        // 本机实测：-XX:+UseSerialGC → Copy|MarkSweepCompact，-XX:+UseParallelGC → PS MarkSweep|PS Scavenge，
        // -XX:+UseZGC → ZGC Cycles|ZGC Pauses。按 flag 名去猜 bean 名一定猜错，
        // 所以 /api/jvm/gc 和报告里都把 bean 原名打出来，让读者自己对照
        assertTrue(run("Serial", List.of("-XX:+UseSerialGC")).get("gc.beans").contains("MarkSweepCompact"),
                "Serial 的 bean 名是 Copy/MarkSweepCompact");
        assertTrue(run("ZGC", List.of("-XX:+UseZGC")).get("gc.beans").contains("ZGC"));
    }

    @Test
    @DisplayName("分配量按 rounds×churnMb+liveMb 计，可以手算")
    void allocatedMbIsTheIntendedAmount() {
        Map<String, String> report = run("Parallel", List.of("-XX:+UseParallelGC"));

        // 10 轮 × 8 MB + 4 MB 常驻 = 84 MB
        assertEquals("84.0", report.get("workload.allocatedMb"));
        // -Xmx128m 时本机 Parallel 报的是 123.0 MB，而 Serial/G1/ZGC 报 128.0：
        // 堆要按收集器自己的粒度对齐，所以「参数值」和「Runtime.maxMemory()」可以差几 MB。
        // 断言区间而不是等号，正是为了让这条差异留在文档里而不是被测试改没
        double xmx = Double.parseDouble(report.get("heap.xmxMb"));
        assertTrue(xmx > 120 && xmx <= 128.0, "heap.xmxMb=" + xmx);
        // 进程内看到的 GC 次数只作对账用：它是所有收集器 bean 的增量之和，
        // 为 0 意味着负载压根没触发回收，那对比表里的 gc.log 行数也就无从解释
        assertTrue(Long.parseLong(report.get("gc.countInProcess")) > 0);
    }
}
