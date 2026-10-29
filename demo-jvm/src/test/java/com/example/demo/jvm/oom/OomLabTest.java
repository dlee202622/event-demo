package com.example.demo.jvm.oom;

import com.example.demo.jvm.lab.ChildJvm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 3 的回归测试：每种 OOM 都要「炸对那一句」，而不是只炸一次。
 *
 * <p>为什么必须起子进程测：OOM 是进程级事件，一旦在测试 JVM 里触发 {@code -Xmx} 上限，
 * 整个 surefire fork 就没了，后面的测试全部丢失。{@link ChildJvm} 让每种溢出各占一个进程，
 * 参数（{@code -Xmx}/{@code -Xss}/{@code -XX:MaxMetaspaceSize}）也能各配各的。</p>
 *
 * <p>断言为什么盯 {@code asExpected} 而不是 {@code reproduced}：后者只说「抛异常了」。
 * 本机实测 {@code --case=gcOverhead} 抛的是 {@code Java heap space}（HotSpot 先撞堆上限，
 * 根本没走到 GC 时间占比 98% 那条规则），只看 reproduced 会把这条误报成复现成功。</p>
 */
class OomLabTest {

    /** 跑一个 case，返回它的 key=value 报告。 */
    private static Map<String, String> run(List<String> flags, String caseName, String... extraArgs) {
        List<String> args = new java.util.ArrayList<>();
        args.add("--case=" + caseName);
        args.addAll(List.of(extraArgs));
        ChildJvm.Result result = ChildJvm.run(flags, OomLab.class.getName(), args, 120_000);
        Map<String, String> report = result.report();
        assertNotNull(report.get("case"), "子进程没吐出报告，退出码=" + result.exitCode()
                + " stderr=" + result.stderr());
        return report;
    }

    @Test
    @DisplayName("堆：Java heap space，且 -Xmx 就是那条规则")
    void heapOverflow() {
        Map<String, String> report = run(List.of("-Xms64m", "-Xmx64m"), "heap", "--chunkMb=8");

        assertEquals("true", report.get("asExpected"));
        assertEquals("java.lang.OutOfMemoryError", report.get("errorClass"));
        assertTrue(report.get("errorMessage").contains("Java heap space"), report.get("errorMessage"));
        // 64 MB 堆 + 8 MB 一块：本机第 6 块申请不下来（前 5 块加 JVM 自身的常驻对象刚好占满）。
        // 这里刻意只断言区间不断言确切次数——确切值取决于启动后堆里已经住了多少东西，
        // 换 JDK 小版本或换收集器就会差一两块；数量级才是这条案例的稳定结论。
        assertEquals("64.0", report.get("flag.MaxHeapSizeMb"));
        int iterations = Integer.parseInt(report.get("iterationsBeforeFailure"));
        assertTrue(iterations >= 4 && iterations <= 10, "iterationsBeforeFailure=" + iterations);
    }

    @Test
    @DisplayName("栈：StackOverflowError 没有消息，报告里必须写成 (无消息) 而不是 null")
    void stackOverflow() {
        Map<String, String> report = run(List.of("-Xss256k"), "stack");

        assertEquals("true", report.get("asExpected"));
        assertEquals("java.lang.StackOverflowError", report.get("errorClass"));
        // 这条是最容易被改坏的：Throwable.getMessage() 对 SOE 返回 null，
        // 一旦有人图省事写 String.valueOf，文档里就会出现字面量 "null"
        assertEquals("(无消息)", report.get("errorMessage"));
        assertTrue(report.get("topFrame").contains("OomLab"), report.get("topFrame"));
    }

    @Test
    @DisplayName("元空间：炸在 48 MB 上限，而自己计数的字节远小于它——堆账本看不见 Metaspace")
    void metaspaceOverflow() {
        Map<String, String> report = run(List.of("-XX:MaxMetaspaceSize=48m"), "metaspace");

        assertEquals("true", report.get("asExpected"));
        assertEquals("java.lang.OutOfMemoryError", report.get("errorClass"));
        assertEquals("Metaspace", report.get("errorMessage"));
        assertEquals("48.0 MB", report.get("pool.MetaspaceMax"));

        // selfCountedAllocMb 只算了「复制进去的字节码」，真正吃掉的还有每个 Class 的
        // klass 结构与常量池，所以它一定明显小于 48 MB。差值不是 bug，是元空间开销本身。
        double counted = Double.parseDouble(report.get("selfCountedAllocMb"));
        assertTrue(counted > 0 && counted < 48, "selfCountedAllocMb=" + counted);
    }

    @Test
    @DisplayName("直接内存：堆几乎没涨，越的是堆外上限")
    void directBufferOverflow() {
        Map<String, String> report = run(List.of("-Xmx64m", "-XX:MaxDirectMemorySize=48m"),
                "direct", "--chunkMb=16");

        assertEquals("true", report.get("asExpected"));
        assertTrue(report.get("errorMessage").contains("direct buffer memory"), report.get("errorMessage"));
        // 这是本案例最想给初学者的一句话：释放堆内引用后堆几乎是空的，
        // 但直接内存仍占满 48 MB——所以「堆 dump 里找不到泄漏」不等于「没泄漏」
        assertTrue(Double.parseDouble(report.get("probe.heapUsedAfterReleaseMb")) < 10,
                "heapUsedAfterReleaseMb=" + report.get("probe.heapUsedAfterReleaseMb"));
        assertEquals(48.0, Double.parseDouble(report.get("probe.directUsedAfterReleaseMb")), 0.5);
    }

    @Test
    @DisplayName("gcOverhead：实测炸成 Java heap space，asExpected 必须是 false（诚实的阴性）")
    void gcOverheadLimitNotReached() {
        Map<String, String> report = run(List.of("-Xmx64m", "-XX:+UseSerialGC"), "gcOverhead");

        assertEquals("true", report.get("reproduced"));
        // 不是测试写错了：这条断言就是用来钉住「JDK 21 上这条 OOM 复现不出来」这个事实，
        // 哪天 HotSpot 改了判定顺序、真的抛 GC overhead limit exceeded，这条会红，
        // 提醒我们更新文档而不是让读者继续相信旧结论
        assertEquals("false", report.get("asExpected"));
        assertTrue(report.get("expectedError").contains("GC overhead limit exceeded"),
                report.get("expectedError"));
        assertTrue(report.get("errorClass").contains("OutOfMemoryError"), report.get("errorClass"));
    }
}
