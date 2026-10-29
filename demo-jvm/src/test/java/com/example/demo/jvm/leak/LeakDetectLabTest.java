package com.example.demo.jvm.leak;

import com.example.demo.jvm.lab.ChildJvm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 场景 6 的端到端测试：泄漏版的可达堆按阶段累加，修好版必须持平。
 *
 * <p>为什么测的是脚本的 stdout 而不是内部方法：这条链路里最容易坏的是<em>取证方式</em>本身——
 * {@code jcmd} 的 attach、不带 {@code -all} 的直方图语义、{@code <pid>:} 表头行的跳过。
 * 把这些绕过去直接测差值算法，等于什么都没测。所以这里跑真实的
 * {@code LeakDetectLab --cases=staticCollection}，再从它自己打印的表格里读两个数。</p>
 *
 * <p>解析自己打印的表格还顺带钉住了两件事（都是第一次跑真失败过才学会的）：
 * 表格行与「每个 case 的修法」说明行首列同名，得靠空行切段；
 * 主表与 intern 证明表的列形状也一样，得靠表头认表。这两条现在都写在 {@code runTable} 里。</p>
 *
 * <p>只跑一个 case：整套 4 个 case 要起 8 个子进程、拍 16 次直方图，本机一分多钟；
 * CI 里这条用 {@code --cases} 过滤，本地想看全量直接不带参数运行即可。</p>
 */
class LeakDetectLabTest {

    /**
     * @return case → fixed → 该行数字（live1MB / live2MB / growthMB）
     */
    private static Map<String, double[]> runTable(String cases, int items, int chunkKb, int heapMb) {
        ChildJvm.Result result = ChildJvm.run(List.of(), LeakDetectLab.class.getName(),
                List.of("--cases=" + cases, "--items=" + items, "--chunkKb=" + chunkKb,
                        "--heapMb=" + heapMb), 240_000);
        assertTrue(result.exitCode() == 0, "LeakDetectLab 非 0 退出：" + result.stderr());

        Map<String, double[]> byFixed = new java.util.LinkedHashMap<>();
        // 只吃主表，不吃后面那张 intern 证明表：两张表的行首都是「caseName + true/false」，
        // 光按列数和「能不能解析成 double」筛会把证明表的 77 条当成 77 MB——
        // 第一次跑就是这么读到 growth=77 的。用表头决定当前在读哪张表才靠得住。
        boolean inMainTable = false;
        for (String line : result.stdout().lines().toList()) {
            // 空行结束当前表：主表下面紧跟「字段说明」和「每个 case 的修法 ×1：static 集合只进不出」，
            // 那行首列同样是 caseName，不切断的话会被当成数据行（实测抛 NumberFormatException）
            if (line.isBlank()) {
                inMainTable = false;
                continue;
            }
            String[] cols = line.trim().split("\\s+");
            if (cols.length >= 5 && cols[0].equals("case")) {
                // 主表表头第五列写死是 growthMB；证明表的表头是 P2条数GC后，据此分流
                inMainTable = cols[4].equals("growthMB");
                continue;
            }
            if (!inMainTable || cols.length < 5 || !cols[0].equals(cases)) {
                continue;
            }
            byFixed.put(cols[1], new double[]{
                    Double.parseDouble(cols[2]), Double.parseDouble(cols[3]), Double.parseDouble(cols[4])});
        }
        return byFixed;
    }

    @Test
    @DisplayName("static 集合：扣掉背景基线后，泄漏的可达堆正好翻倍")
    void leakDoubles() {
        // 50 条 × 64 KB = 每阶段 3.2 MB，128 MB 堆里离上限还远，测的是纯增长不是溢出
        Map<String, double[]> rows = runTable("staticCollection", 50, 64, 128);

        assertEquals(2, rows.size(), "泄漏版和修好版都要有一行，实际=" + rows.keySet());
        double[] leak = rows.get("false");
        double growth = leak[2];
        assertTrue(growth > 2.5 && growth < 4.0, "泄漏版 Δ 应接近 3.2 MB，实际=" + growth);
        // 别直接断言 live2 > live1 * 1.8：本机实测 4.49 → 7.62，只有 1.70 倍。
        // 因为可达堆里还压着约 1.3 MB 的运行时背景（线程栈关联对象、类元数据、JFR 缓冲…），
        // 「翻倍」说的是漏进去的那部分，不是整个堆。扣掉同一版本序列里修好行的基线再比，
        // 才是这个实验真正的结论——断言写成减法式，读者照抄命令也能复现出 2.0 倍。
        double baseline = rows.get("true")[0];
        double phase1Leaked = leak[0] - baseline;
        double phase2Leaked = leak[1] - baseline;
        assertTrue(phase2Leaked / phase1Leaked > 1.7 && phase2Leaked / phase1Leaked < 2.3,
                "扣基线后 " + phase1Leaked + " -> " + phase2Leaked
                        + "，比值=" + String.format("%.2f", phase2Leaked / phase1Leaked));
    }

    @Test
    @DisplayName("修好版：活儿照样干，可达堆基本不涨")
    void fixedStaysFlat() {
        Map<String, double[]> rows = runTable("staticCollection", 50, 64, 128);

        double[] fixed = rows.get("true");
        // 不断言等于 0：修好版照样分配同样多的对象，只是不再留住引用，
        // 剩下的零点几 MB 是线程栈、类元数据这类背景。真为 0 反而说明负载没跑。
        assertTrue(Math.abs(fixed[2]) < 0.6, "修好版 Δ 应接近 0，实际=" + fixed[2]);
        // 两阶段的基线要稳：说明直方图读的是同一个进程、同一套统计口径
        assertTrue(Math.abs(fixed[1] - fixed[0]) < 0.6, fixed[0] + " vs " + fixed[1]);
    }
}
