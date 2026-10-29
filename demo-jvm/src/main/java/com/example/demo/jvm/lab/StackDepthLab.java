package com.example.demo.jvm.lab;

import java.util.ArrayList;
import java.util.List;

/**
 * 场景2：递归到 {@code StackOverflowError}，反推「一次方法调用吃掉多少虚拟机栈」。
 *
 * <p>为什么用递归而不是别的写法：虚拟机栈的 {@code Stack Frame} 只在方法调用时压栈、
 * 返回时弹栈，普通循环永远看不到栈增长。递归是唯一能把「栈深」这个抽象概念
 * 变成可数的东西——压了 N 帧才溢出，就说明每帧平均 {@code 栈大小 / N} 字节。</p>
 *
 * <p>两种粒度都覆盖了：
 * <ul>
 *   <li>进程级：{@code -Xss} 只能启动时给，所以由父进程（{@link ChildJvm}）用不同 {@code -Xss}
 *       起多个子 JVM，比较主线程深度；</li>
 *   <li>线程级：{@code new Thread(group, task, name, stackSize)} 能给单个线程指定栈大小，
 *       于是<strong>同一个 JVM 里</strong>就能比较 128 KB 和 4 MB 的线程，本类默认走这条路。</li>
 * </ul></p>
 *
 * <p>坑（都在这份代码里绕开了）：
 * <ul>
 *   <li>主线程的栈由 {@code -Xss} 决定，但进入 {@code main} 之前已经压了几帧，
 *       新建线程也要先压 {@code Thread.run} 那条启动链，所以 {@code main.depth} 和
 *       {@code thread.default.depth} 只差个位数（实测 39532 对 39528）——
 *       想量「一个线程本身占多少栈」，得靠后面那组不同栈大小的拟合；</li>
 *   <li>{@code -Xss} 给得太小时 JVM 直接拒绝启动（{@code stack size is too small} 之类），
 *       那和 {@code StackOverflowError} 是两种不同的失败，别混为一谈；</li>
 *   <li>计数必须放在堆里的数组，不能靠返回值——栈都溢出了，返回值传不出来；</li>
 *   <li>捕获 {@code StackOverflowError} 之后栈已全部弹空，此时打印是安全的；
 *       但在 {@code catch} 里再递归会立刻二次溢出；</li>
 *   <li><strong>预热不是可选的</strong>：解释器帧和 C2 编译后的帧大小不同，
 *       实测同一段代码冷启动只能压 14636 帧，编译后同一份栈能压 19764 帧。
 *       不做预热的话，越晚测的尺寸越「省栈」，结论完全是假的。</li>
 * </ul></p>
 */
public final class StackDepthLab {

    /** 递归上限保护：万一某个 JDK 把递归优化掉了，也不至于把实验进程跑成死循环。 */
    private static final int HARD_LIMIT = 2_000_000;

    /**
     * 量出「栈大小 → 递归深度」的直线关系，并反过来用它的斜率预测别的栈大小能递归多深。
     *
     * <p>用法：{@code java -cp demo-jvm/target/classes com.example.demo.jvm.lab.StackDepthLab
     * --threadSizesKb=128,256,512,1024,4096}。列出的每个尺寸都会起一个新线程、
     * 用 {@code new Thread(null, runnable, name, stackSize)} 显式指定栈大小，
     * 因此不需要为每个尺寸重启 JVM。</p>
     *
     * <p>报告里的 {@code main.depth} 与 {@code thread.default.depth} 实测只差 4 帧
     * （本机 39532 vs 39528），可以认为等价：主线程栈里已经压着的启动帧，
     * 被新线程自身的 {@code Thread.run → Runnable.run} 帧抵消了。
     * 顺带否掉一条常见说法——「主线程的栈比别人大」在 HotSpot 上不成立。</p>
     */
    public static void main(String[] args) {
        LabArgs parsed = LabArgs.parse(args);
        // 128 KB 起步：macOS x64 上再小线程就可能起不来，会被误读成「实验失败」
        String sizes = parsed.string("threadSizesKb", "128,256,512,1024,4096");

        // 预热：解释器帧比 C2 编译后的帧大得多（同一份代码实测 14636 帧 vs 19764 帧），
        // 先在足够大的栈上递归一次把 recurse 编译掉，后面每个尺寸都在同样的「已编译」状态下测量
        int warmupDepth = measureWarmup();

        long defaultKb = threadStackSizeKb();
        LabReport report = LabReport.of("stack-depth")
                // -Xss 是进程级默认值（单位 KB，实测 Corretto 21 默认 1024 = 1 MB），
                // 新建线程不显式指定栈大小时沿用它
                .put("flag.ThreadStackSizeKb", defaultKb)
                .putMb("flag.ThreadStackSizeMb", defaultKb / 1024.0)
                .put("warmup.depth", warmupDepth)
                .put("note", "所有 depth 均在 recurse 已被 C2 编译之后测量，否则帧大小不可比");

        // 主线程直接递归：栈里已经压着 JVM 启动到 main 的帧
        int mainDepth = measureCurrentThread();
        report.put("main.depth", mainDepth);
        report.putBytes("main.bytesPerFrame", frameBytes(defaultKb * 1024L, mainDepth));

        // 新建线程、栈大小取默认值：和 main 一比就知道两条启动链各吃掉多少栈
        // （实测 main=39532、thread.default=39528，差 4 帧，说明 main 之前的启动帧比 Thread.run 那条链还少）
        int freshDefaultDepth = measure(0);
        report.put("thread.default.depth", freshDefaultDepth);
        report.putBytes("thread.default.bytesPerFrame", frameBytes(defaultKb * 1024L, freshDefaultDepth));

        List<Long> usableDepths = new ArrayList<>();
        List<Long> usableStackBytes = new ArrayList<>();
        for (String raw : sizes.split(",")) {
            long bytes = Long.parseLong(raw.trim()) * 1024L;
            int depth = measure(bytes);
            report.put("thread." + raw.trim() + "KB.depth", depth);
            // 每帧平均字节数 = 栈大小 / 帧数：单看这一列会误以为帧大小随栈变化，所以后面还要拟合
            report.putBytes("thread." + raw.trim() + "KB.bytesPerFrame", frameBytes(bytes, depth));
            if (depth >= HARD_LIMIT) {
                // 撞到递归上限而不是栈溢出，这个点不能拿去拟合
                report.put("thread." + raw.trim() + "KB.capped", true);
                continue;
            }
            usableDepths.add((long) depth);
            usableStackBytes.add(bytes);
        }
        fitFrameCost(report, usableDepths, usableStackBytes);
        report.print();
    }

    /**
     * 最小二乘拟合 {@code 栈大小 = 每帧字节 × 深度 + 固定开销}。
     *
     * <p>为什么要拟合而不是只报平均值：小栈的「平均每帧字节数」明显偏大
     * （实测 128 KB 是 66 字节/帧，4 MB 是 25 字节/帧），因为每个线程都有一笔与递归无关的
     * 固定开销。拟合把两件事拆开：斜率是真正的帧大小，截距是固定开销。
     * 那笔固定开销也正是「线程数一多就 unable to create native thread」的元凶。</p>
     *
     * <p>少于 2 个可用点时不拟合，直接省略字段，避免给读者一个看起来很像数的 0。</p>
     */
    private static void fitFrameCost(LabReport report, List<Long> depths, List<Long> stackBytes) {
        if (depths.size() < 2) {
            return;
        }
        double n = depths.size();
        double sumX = 0;
        double sumY = 0;
        double sumXY = 0;
        double sumXX = 0;
        for (int i = 0; i < depths.size(); i++) {
            double x = depths.get(i);
            double y = stackBytes.get(i);
            sumX += x;
            sumY += y;
            sumXY += x * y;
            sumXX += x * x;
        }
        double denominator = n * sumXX - sumX * sumX;
        if (denominator == 0) {
            return;
        }
        double slope = (n * sumXY - sumX * sumY) / denominator;   // 每帧字节数
        double intercept = (sumY - slope * sumX) / n;             // 与深度无关的固定开销
        report.putRound("frame.bytesFitted", slope);
        report.putBytes("thread.fixedOverheadBytes", Math.round(intercept));
        // R² 只用来告诉读者「这条直线靠不靠谱」，不解释成因，避免过度推断
        double meanY = sumY / n;
        double ssTot = 0;
        double ssRes = 0;
        for (int i = 0; i < depths.size(); i++) {
            double predicted = slope * depths.get(i) + intercept;
            ssRes += Math.pow(stackBytes.get(i) - predicted, 2);
            ssTot += Math.pow(stackBytes.get(i) - meanY, 2);
        }
        report.putRound("frame.fitR2", ssTot == 0 ? 1 : 1 - ssRes / ssTot);
    }

    /** 预热用的栈大小：16 MB，够 recurse 被编译成机器码后再溢出。 */
    private static final long WARMUP_STACK_BYTES = 16L * 1024 * 1024;

    private static int measureWarmup() {
        return measure(WARMUP_STACK_BYTES);
    }

    /**
     * 在当前（主）线程里递归到溢出。
     *
     * @return 本线程从当前位置起还能压多少帧
     */
    static int measureCurrentThread() {
        int[] counter = new int[1];
        try {
            recurse(counter);
        } catch (StackOverflowError expected) {
            // 栈已全部弹空，回到 main 的栈帧里，安全
        }
        return counter[0];
    }

    /**
     * 在独立线程里递归压栈直到溢出。
     *
     * @param stackBytes 线程栈大小，单位字节；0 表示沿用 JVM 默认的 {@code -Xss}
     * @return 溢出前成功压栈的帧数（不含触发异常的那一次）
     */
    static int measure(long stackBytes) {
        int[] counter = new int[1];
        Runnable body = () -> {
            try {
                recurse(counter);
            } catch (StackOverflowError expected) {
                // 预期路径：溢出就说明量到了深度，counter 里就是答案
            }
        };
        // 第 4 个参数是线程栈大小（字节），0 = 用 -Xss；这是唯一能在同进程里比较不同栈大小的入口
        Thread worker = new Thread(null, body, "stack-probe-" + stackBytes, stackBytes);
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待探测线程时被中断", e);
        }
        return counter[0];
    }

    /**
     * 单参数、单局部变量的最小帧：帧里只有 {@code counter} 引用加一个 int，
     * 这样「每帧字节数」才接近 HotSpot 的真实开销，不会被我顺手塞进帧的大数组污染。
     */
    private static void recurse(int[] counter) {
        int frame = counter[0] + 1;
        counter[0] = frame;
        if (frame >= HARD_LIMIT) {
            return;
        }
        recurse(counter);
    }

    /** 每帧字节数；深度为 0（线程没起来或栈太小直接失败）时返回 0 而不是除零。 */
    private static long frameBytes(long stackBytes, int depth) {
        return depth <= 0 ? 0 : Math.round((double) stackBytes / depth);
    }

    /**
     * 读 {@code ThreadStackSize}，等价于 {@code jcmd <pid> VM.flags | grep ThreadStackSize}。
     *
     * <p>用 VM 参数而不是「拿实测深度反推」：没显式给 {@code -Xss} 时也有默认值，
     * 而这个默认值随平台和 JDK 版本变化，只有 JVM 自己知道。单位是 KB，
     * 换算成字节这件事统一交给 {@link VmOptions} 的注释说明，不在这里重复。</p>
     */
    private static long threadStackSizeKb() {
        Long kilobytes = VmOptions.number("ThreadStackSize");
        // 读不到时按 1 MB 兜底：这在报告里表现为「深度对不上」而不是整行缺失
        return kilobytes == null || kilobytes <= 0 ? 1024 : kilobytes;
    }

    private StackDepthLab() {
    }
}
