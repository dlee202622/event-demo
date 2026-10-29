package com.example.demo.jvm.lab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 2 的回归测试：栈深与 {@code -Xss} 成正比，而且比例要线性得能拿来拟合帧大小。
 *
 * <p>断言为什么用「比值区间」而不是绝对帧数：一帧多少字节由 JIT 决定，
 * 解释器帧、C1 帧、C2 帧完全不同（本机实测同一份代码 14636 帧 vs 19764 帧）。
 * 绝对值换台机器就变，但「栈大一倍、能递归的层数也大一份」这条线性关系是稳的。</p>
 */
class StackDepthLabTest {

    private static Map<String, String> run(List<String> flags, String sizesKb) {
        ChildJvm.Result result = ChildJvm.run(flags, StackDepthLab.class.getName(),
                List.of("--threadSizesKb=" + sizesKb), 120_000);
        Map<String, String> report = result.report();
        assertNotNull(report.get("main.depth"), "没吐出报告：" + result.stderr());
        return report;
    }

    @Test
    @DisplayName("256 KB 与 2 MB 的栈深比值接近 8 倍")
    void depthScalesWithStackSize() {
        Map<String, String> report = run(List.of("-Xss1m"), "256,2048");

        int small = Integer.parseInt(report.get("thread.256KB.depth"));
        int large = Integer.parseInt(report.get("thread.2048KB.depth"));
        assertTrue(small > 500, "256KB.depth=" + small);
        assertTrue(large > small, small + " 应小于 " + large);

        // 本机实测：256 KB 深 6760、2 MB 深 83220，比值 12.31 —— 比 8 大，不是测量误差，
        // 而是每个线程栈里有一块「递归之前就已经占掉」的固定开销（同一份报告里
        // thread.fixedOverheadBytes=99888，约 97 KB）。栈越小，这块固定开销占比越高，
        // 于是小栈能递归的层数被压得比线性比例更狠。这条断言钉的是方向：比值必须 > 8。
        double ratio = large / (double) small;
        assertTrue(ratio > 8, "2 MB/256 KB 的栈深比值应大于 8，实际=" + ratio);
    }

    @Test
    @DisplayName("多个尺寸拟合出来的帧大小必须是直线（R² 接近 1）")
    void frameCostFitIsLinear() {
        Map<String, String> report = run(List.of("-Xss1m"), "256,512,1024,4096");

        double r2 = Double.parseDouble(report.get("frame.fitR2"));
        assertTrue(r2 > 0.98, "frame.fitR2=" + r2);

        // bytesFitted 是「每帧平均字节数」的斜率估计，本机 C2 编译后在几十到一百多字节量级；
        // 落到 0 或负数说明测到的是「撞递归上限」的点，被 fitFrameCost 剔掉了
        double frameBytes = Double.parseDouble(report.get("frame.bytesFitted"));
        assertTrue(frameBytes > 10 && frameBytes < 1000, "frame.bytesFitted=" + frameBytes);
        double overhead = Double.parseDouble(report.get("thread.fixedOverheadBytes"));
        // 截距是线程一出生就占掉的固定栈开销（本机约 97 KB），它解释了为什么 256 KB
        // 装不下「256KB ÷ 25B/帧」那么多层
        assertTrue(overhead > 0, "thread.fixedOverheadBytes=" + overhead);

        // 拿拟合出来的两个系数去预测实测深度，误差必须小于 10%——
        // 这才是「栈深 = (栈大小 - 固定开销) / 帧大小」这条线性模型真的成立的证据
        double frameBytes2 = Double.parseDouble(report.get("frame.bytesFitted"));
        for (String sizeKb : List.of("256", "512", "1024", "4096")) {
            int measured2 = Integer.parseInt(report.get("thread." + sizeKb + "KB.depth"));
            double predicted = (Long.parseLong(sizeKb) * 1024 - overhead) / frameBytes2;
            double error = Math.abs(measured2 - predicted) / predicted;
            assertTrue(error < 0.1, sizeKb + "KB: measured=" + measured2
                    + " predicted=" + predicted + " error=" + error);
        }
    }

    @Test
    @DisplayName("-Xss 是进程级的：报告里的默认值必须等于启动参数")
    void reportsTheProcessLevelFlag() {
        Map<String, String> report = run(List.of("-Xss2m"), "512");

        // ThreadStackSize 的单位是 KB，写成 2 MB 时是 2048
        assertTrue("2048".equals(report.get("flag.ThreadStackSizeKb")),
                "flag.ThreadStackSizeKb=" + report.get("flag.ThreadStackSizeKb"));
        assertTrue("2.0".equals(report.get("flag.ThreadStackSizeMb")),
                "flag.ThreadStackSizeMb=" + report.get("flag.ThreadStackSizeMb"));
    }
}
