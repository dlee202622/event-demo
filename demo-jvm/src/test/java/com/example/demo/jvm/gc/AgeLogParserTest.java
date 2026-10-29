package com.example.demo.jvm.gc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AgeLogParser} 的回归测试，夹具是本机 JDK 21 真实落盘的日志行（不是手写的理想格式）。
 *
 * <p>为什么要用真日志：{@code -Xlog} 的输出没有稳定契约，JDK 每次小版本都可能改列名、
 * 加空格、把 debug 级降成 trace 级。手写字段的测试会「永远通过」，
 * 而复制过来的真实行一旦哪天解析不出来，测试会立刻告诉我们。</p>
 *
 * <p>每个断言旁边写了「这个数字是从哪几行算出来的」，
 * 读者可以拿 {@code demo-jvm-lab/age-serial-default-*.log} 自己复算。</p>
 */
class AgeLogParserTest {

    /** Serial、64 MB 堆、默认参数下前三个 Young GC 周期，直接取自实测文件。 */
    private static final String SERIAL_LOG = """
            [0.098s][debug][gc,age      ] GC(0) Desired survivor size 1114112 bytes, new threshold 1 (max threshold 15)
            [0.098s][trace][gc,age      ] GC(0) Age table with threshold 1 (max threshold 15)
            [0.098s][trace][gc,age      ] GC(0) - age   1:    2228224 bytes,    2228224 total
            [0.098s][info ][gc,heap     ] GC(0) DefNew: 17464K(19648K)->2176K(19648K) Eden: 17464K(17472K)->0K(17472K) From: 0K(2176K)->2176K(2176K)
            [0.098s][info ][gc,heap     ] GC(0) Tenured: 1056K(43712K)->14473K(43712K)
            [0.098s][info ][gc,metaspace] GC(0) Metaspace: 616K(768K)->616K(768K) NonClass: 552K(640K)->552K(640K) Class: 63K(128K)->63K(128K)
            [0.098s][info ][gc          ] GC(0) Pause Young (Allocation Failure) 18M->16M(61M) 9.083ms
            [0.106s][debug][gc,age      ] GC(1) Desired survivor size 1114112 bytes, new threshold 1 (max threshold 15)
            [0.106s][trace][gc,age      ] GC(1) Age table with threshold 1 (max threshold 15)
            [0.106s][trace][gc,age      ] GC(1) - age   1:    2198624 bytes,    2198624 total
            [0.106s][info ][gc,heap     ] GC(1) DefNew: 19627K(19648K)->2147K(19648K) Eden: 17451K(17472K)->0K(17472K) From: 2176K(2176K)->2147K(2176K)
            [0.106s][info ][gc,heap     ] GC(1) Tenured: 14473K(43712K)->22666K(43712K)
            [0.106s][info ][gc          ] GC(1) Pause Young (Allocation Failure) 33M->24M(61M) 6.412ms
            [0.112s][debug][gc,age      ] GC(2) Desired survivor size 1114112 bytes, new threshold 1 (max threshold 15)
            [0.112s][trace][gc,age      ] GC(2) - age   1:    2198624 bytes,    2198624 total
            [0.112s][info ][gc,heap     ] GC(2) Tenured: 22666K(43712K)->29709K(43712K)
            [0.112s][info ][gc          ] GC(2) Pause Young (Allocation Failure) 39M->31M(61M) 5.981ms
            """;

    @Test
    @DisplayName("Serial：动态年龄把阈值压到 1，老年代按正增量累加")
    void parsesSerialAgeTable() {
        AgeLogParser.AgeSummary summary = AgeLogParser.parse("serial-default", SERIAL_LOG);

        // 三个周期都有 Desired survivor size 行，也都有 - age N: 行（后者要 gc+age=trace 才打得出来）
        assertEquals(3, summary.cycles());
        assertEquals(3, summary.cyclesWithAge());
        assertEquals(3, summary.youngGcs());

        // 1114112 / 1024 = 1088 KB，即 Survivor 的目标容量
        assertEquals(1088, summary.desiredSurvivorKb());
        // 参数没改过，所以 max threshold 一直是 15；但实际生效的新阈值三次都是 1
        assertEquals(15, summary.maxTenuringThreshold());
        assertEquals("[1]", summary.thresholdsSeen());
        assertEquals(3, summary.thresholdOneCycles());
        assertEquals(1, summary.maxAgeSeen());
        // 这就是场景 5 想教的那件事：MaxTenuringThreshold=15 在满载的小堆上从来没生效过
        assertEquals(true, summary.tenuredEarly());

        // Survivor 峰值取 age 行的 total 列最大值：2228224 / 1024 = 2176 KB
        assertEquals(2176, summary.survivorPeakKb());
        // 晋升 = 每个周期老年代正增量之和，按周期各自除 1024（不是先求和再除，否则会多算）
        //   (14473-1056)/1024=13  (22666-14473)/1024=8  (29709-22666)/1024=6  -> 27
        assertEquals(27, summary.promotedMb());
        assertEquals(29, summary.oldGenEndMb());

        // Serial 的小堆里既没有大对象触发也没有 Full GC，这三列必须都是 0，
        // 否则说明 cause 关键字的匹配把别的收集器的行也吃了
        assertEquals(0, summary.humongousTriggers());
        assertEquals(0, summary.evacuationFailures());
        assertEquals(0, summary.fullGcs());
    }

    /** G1、region=2M、1 MB 对象：验证 region 换算与 humongous 去重。 */
    private static final String G1_LOG = """
            [0.013s][info][gc,init] Heap Region Size: 2M
            [0.126s][info][gc,start    ] GC(0) Pause Young (Concurrent Start) (G1 Humongous Allocation)
            [0.126s][debug][gc,age      ] GC(0) Desired survivor size 2097152 bytes, new threshold 15 (max threshold 15)
            [0.126s][trace][gc,age      ] GC(0) - age   1:     524288 bytes,     524288 total
            [0.128s][info ][gc,heap     ] GC(0) Old regions: 1->2
            [0.128s][info ][gc          ] GC(0) Pause Young (Concurrent Start) (G1 Humongous Allocation) 22M->20M(64M) 1.908ms
            [0.148s][info ][gc,start    ] GC(8) Pause Full (G1 Compaction Pause)
            [0.152s][info ][gc          ] GC(8) Pause Full (G1 Compaction Pause) 58M->25M(64M) 3.944ms
            """;

    @Test
    @DisplayName("G1：region 数换算成 MB，同一个周期的 cause 只算一次")
    void parsesG1RegionMathAndDedupesCauses() {
        AgeLogParser.AgeSummary summary = AgeLogParser.parse("g1-1m-humongous", G1_LOG);

        // 「G1 Humongous Allocation」在 [gc,start] 和 [gc] 两行里各出现一次，
        // 按 GC(0) 去重后是 1 次；如果这里得到 2，说明数的是行数而不是周期数
        assertEquals(1, summary.humongousTriggers());
        // Full GC 也一样：GC(8) 两行 -> 1 次
        assertEquals(1, summary.fullGcs());

        // Old regions 1->2，region 2M => 晋升 2 MB
        assertEquals(2, summary.promotedMb());
        assertEquals(4, summary.oldGenEndMb());

        // 阈值没被压下来：G1 这里 new threshold 就是 15
        assertEquals("[15]", summary.thresholdsSeen());
        assertEquals(0, summary.thresholdOneCycles());
    }

    @Test
    @DisplayName("ZGC 那类没有年龄表的日志：全是 0 而不是异常")
    void toleratesLogWithoutAgeTable() {
        // 非分代 ZGC 压根不打 gc,age，解析结果必须安静地给 0，
        // 让表格里的 0 表示「确实没有」而不是「解析器坏了」
        AgeLogParser.AgeSummary summary = AgeLogParser.parse("zgc", "[0.010s][info][gc] Using ZGC\n");

        assertEquals(0, summary.cycles());
        assertEquals(0, summary.cyclesWithAge());
        assertEquals("[]", summary.thresholdsSeen());
    }
}
