package com.example.demo.jvm.lab;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 用当前 JDK 再启一个子 JVM 跑实验类，捕获输出与退出码。
 *
 * <p>为什么需要它：JVM 参数是<strong>进程级</strong>的。{@code -Xmx}、{@code -Xss}、
 * {@code -XX:+UseZGC} 只能在启动时决定，一个常驻进程没法同时是 G1 又是 ZGC。
 * 所以「对比四种收集器」「复现五种 OOM」这类场景只能靠起子进程，
 * 测试也一样——这样文档里的数字和 CI 里断言的数字来自同一条执行路径。</p>
 *
 * <p>关键实现：
 * <ul>
 *   <li>可执行文件取 {@code java.home}，不用 PATH 上的 java，避免 mvn 用 JDK 21 而 shell 用 JDK 8；</li>
 *   <li>classpath 取 {@code java.class.path}，测试进程里它就是 target/classes + 依赖，
 *       所以子 JVM 不需要 Maven 也能加载实验类；</li>
 *   <li>stdout / stderr 各自异步读取：只有 OOM 这类进程 stderr 会写满，
 *       同步读 stdout 再 waitFor 会因管道阻塞死锁。</li>
 * </ul></p>
 *
 * <p>坑：子 JVM 抛 OutOfMemoryError 时退出码是 1（HotSpot 对未捕获 OOM 的处理），
 * 别把「失败」误判成「实验没生效」——实验恰恰需要它挂掉，见 {@link Result#exitCode()}。</p>
 */
public final class ChildJvm {

    /** 默认超时 60 秒：所有实验在参数默认值下都在几秒内结束，超时就说明卡住了。 */
    public static final long DEFAULT_TIMEOUT_MS = 60_000;

    private ChildJvm() {
    }

    /**
     * @param exitCode 进程退出码，0 表示正常结束；OOM 类实验预期是非 0
     * @param stdout   标准输出，通常是 {@link LabReport} 的 key=value 文本
     * @param stderr   标准错误，异常栈与 JVM 告警在这里
     */
    public record Result(int exitCode, String stdout, String stderr, long elapsedMs) {

        /**
         * 把 stdout 当 {@link LabReport} 解析。
         *
         * <p>只对「子进程正常打完报告」有意义；OOM 类实验的 stdout 可能是半截，
         * 那种用例请直接用 {@code stdout}/{@code stderr} 字符串断言，不要指望这里能解析全。</p>
         */
        public Map<String, String> report() {
            return LabReport.parse(stdout);
        }

        /** stdout + stderr 拼接，用于断言异常信息出现。 */
        public String allOutput() {
            return stdout + "\n" + stderr;
        }
    }

    /**
     * 启动子 JVM。
     *
     * @param jvmFlags {@code -XX}、{@code -X} 参数，按顺序放在主类之前
     * @param mainClass 全限定主类名
     * @param arguments 传给 {@code main} 的参数，形如 {@code --case=heap}
     * @param timeoutMs 超时毫秒；超时强杀并抛异常，避免测试挂死
     * @return 运行结果
     */
    public static Result run(List<String> jvmFlags, String mainClass, List<String> arguments, long timeoutMs) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.addAll(jvmFlags);
        command.add("-cp");
        command.add(classpath());
        command.add(mainClass);
        command.addAll(arguments);
        return runCommand(command, timeoutMs);
    }

    /**
     * 跑任意 JDK 命令行工具（{@code javap} / {@code jhsdb} / {@code jstat}），不做 java 特化。
     *
     * <p>为什么单独开一个入口：{@code javap -v} 这类工具的输出比 {@code -Xlog} 更啰嗦，
     * 且它们不是 JVM，超时和退出码的语义完全不同（javap 找不到类返回 1，
     * 但没有任何「OOM」概念）。共用同一套「并发读两个流 + 超时强杀」的实现，
     * 免得每个实验各写一遍进程管理。</p>
     *
     * @param command   完整命令，第一个元素是可执行文件绝对路径
     * @param timeoutMs 超时毫秒
     * @return 退出码与两个流的输出
     */
    public static Result runCommand(List<String> command, long timeoutMs) {
        try {
            long start = System.nanoTime();
            Process process = new ProcessBuilder(command).start();
            // 两个流必须并发消费：任一管道写满（约 64 KB）就会让对方阻塞
            CompletableFuture<String> stdout = readAsync(process.getInputStream());
            CompletableFuture<String> stderr = readAsync(process.getErrorStream());
            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("子进程超过 " + timeoutMs + "ms 未退出，命令：" + command);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            return new Result(process.exitValue(),
                    stdout.get(5, TimeUnit.SECONDS),
                    stderr.get(5, TimeUnit.SECONDS),
                    elapsedMs);
        } catch (IOException e) {
            throw new IllegalStateException("启动子进程失败：" + e.getMessage() + "，命令：" + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待子进程时被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("读取子进程输出失败：" + e.getMessage(), e);
        }
    }

    /**
     * {@link #run(List, String, List, long)} 的便捷重载，用默认超时。
     *
     * <p>会真正等下去的实验（OOM、GC 对比）都该显式给超时，只有秒级的小实验才用这条。</p>
     */
    public static Result run(List<String> jvmFlags, String mainClass, String... arguments) {
        return run(jvmFlags, mainClass, List.of(arguments), DEFAULT_TIMEOUT_MS);
    }

    /**
     * 起一个「活着等指令」的子 JVM。
     *
     * <p>为什么 {@link #run} 不够用：内存泄漏场景必须在进程<em>还在跑</em>的时候从外面敲
     * {@code jcmd <pid> GC.class_histogram}，等它退出再读输出已经什么都没了。
     * 所以这里返回一个能读写它标准输入输出的会话，由调用方分阶段推进。</p>
     *
     * <p>坑：stdout 必须一边跑一边读。管道的缓冲区只有 64 KB 左右，
     * 子进程打印多了就会卡在 {@code System.out.println} 上，
     * 而父进程正等着它的 {@code READY} 行——两边互相等，就是死锁。
     * 因此读行动作放在一条守护线程里，主线程只从队列取。</p>
     *
     * @param jvmFlags  子 JVM 参数
     * @param mainClass 主类
     * @param arguments 程序参数
     * @return 会话，用完必须 {@link Session#close()}
     */
    public static Session start(List<String> jvmFlags, String mainClass, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.addAll(jvmFlags);
        command.add("-cp");
        command.add(classpath());
        command.add(mainClass);
        command.addAll(arguments);
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
            return new Session(process, String.join(" ", command));
        } catch (IOException e) {
            throw new IllegalStateException("启动常驻子进程失败：" + e.getMessage() + "，命令：" + command, e);
        }
    }

    /**
     * 与一个活着的子 JVM 双向通信的句柄。
     *
     * <p>协议很简单：子进程每完成一个阶段就打印一行 {@code READY phase=N ...}，
     * 父进程敲 jcmd 观测，再往它的 stdin 写一行 {@code GO}/{@code EXIT} 放行下一步。
     * 用 stdin 而不是临时文件/Socket：不需要端口、不依赖文件系统权限，
     * 而且「父进程写一行、子进程读一行」这个模型读者一眼就能看懂。</p>
     */
    public static final class Session implements AutoCloseable {

        /** 子进程吐出的每一行，按顺序进队列，等 {@link #await} 来取。 */
        private final BlockingQueue<String> outLines = new LinkedBlockingQueue<>();
        /** stderr 全文留在内存里，只在超时报错时 dump 一次。 */
        private final StringBuilder errText = new StringBuilder();
        private final Process process;
        private final String commandDescription;
        private final java.io.BufferedWriter in;

        private Session(Process process, String commandDescription) {
            this.process = process;
            this.commandDescription = commandDescription;
            this.in = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            pump(process.getInputStream(), outLines::offer);
            pump(process.getErrorStream(), line -> {
                synchronized (errText) {
                    errText.append(line).append('\n');
                }
            });
        }

        /** 子进程 PID，用来拼 {@code jcmd <pid> ...}。 */
        public long pid() {
            return process.pid();
        }

        /**
         * 等到第一行包含 {@code marker} 的输出。
         *
         * @param marker    例如 {@code "READY phase=2"}
         * @param timeoutMs 超时毫秒；超时会把 stderr 一起抛出来，否则现场全丢了
         * @return 那一行原文
         */
        public String await(String marker, long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            try {
                while (System.currentTimeMillis() < deadline) {
                    String line = outLines.poll(200, TimeUnit.MILLISECONDS);
                    if (line == null) {
                        // 队列空且子进程已经退出：不会再有 READY 了，立刻失败而不是耗满超时
                        if (!process.isAlive()) {
                            break;
                        }
                        continue;
                    }
                    if (line.contains(marker)) {
                        return line;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("等不到子进程输出「" + marker + "」，命令：" + commandDescription
                    + "，stderr：\n" + stderrSnapshot());
        }

        /** 往子进程 stdin 写一行并立即 flush——不 flush 它永远读不到。 */
        public void send(String line) {
            try {
                in.write(line);
                in.newLine();
                in.flush();
            } catch (IOException e) {
                throw new IllegalStateException("向子进程发送「" + line + "」失败：" + e.getMessage(), e);
            }
        }

        /** 等子进程正常退出，超时则强杀并抛异常。 */
        public int waitForExit(long timeoutMs) {
            try {
                if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("子进程 " + timeoutMs + "ms 内没退出，已强杀。stderr：\n"
                            + stderrSnapshot());
                }
                return process.exitValue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待子进程退出时被中断", e);
            }
        }

        private String stderrSnapshot() {
            synchronized (errText) {
                return errText.toString();
            }
        }

        /** 一条守护线程把流按行搬进消费者；进程退出后线程自然结束（守护线程不阻止 JVM 退出）。 */
        private static void pump(InputStream stream, java.util.function.Consumer<String> sink) {
            Thread reader = new Thread(() -> {
                try (var out = new java.io.BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = out.readLine()) != null) {
                        sink.accept(line);
                    }
                } catch (IOException e) {
                    // 进程被强杀时这里必然报错，属于预期，不该影响父进程的结论
                }
            }, "child-jvm-pump");
            reader.setDaemon(true);
            reader.start();
        }

        /**
         * try-with-resources 的收尾：<strong>强杀</strong>而不是 {@code destroy()}。
         *
         * <p>这些子进程跑的都是「故意要 OOM」「故意把堆挤满」的负载，优雅终止信号
         * 经常被打断的分配循环吃掉而不生效，留着就是页面上几十个僵死 JVM。
         * 调用前应先用 {@code send("EXIT")} + {@code waitForExit} 走正常协议，
         * 这里只兜住「异常路径没走到那一步」。</p>
         */
        @Override
        public void close() {
            process.destroyForcibly();
        }
    }

    /** 当前 JDK 的 java 可执行文件绝对路径。 */
    public static String javaExecutable() {
        return java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** 把 {@code java.home} 里的工具（jcmd / jstat / jhsdb）路径也给出来，脚本会用到。 */
    public static String jdkTool(String toolName) {
        return java.nio.file.Path.of(System.getProperty("java.home"), "bin", toolName).toString();
    }

    /**
     * 直接把当前进程的 classpath 转交给子进程，而不是去猜 {@code target/classes} 在哪。
     *
     * <p>这样从 {@code ./mvnw} 跑、从 IDE 跑、还是从 {@code java -cp demo-jvm/target/classes} 跑，
     * 子进程看到的类路径都和父进程一致——实验类不需要额外打包就能复现。</p>
     */
    public static String classpath() {
        return System.getProperty("java.class.path");
    }

    private static CompletableFuture<String> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (InputStream closeable = stream) {
                return new String(closeable.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("读取子进程输出失败：" + e.getMessage(), e);
            }
        });
    }
}
