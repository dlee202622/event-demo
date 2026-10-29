package com.example.demo.jvm.leak;

import com.example.demo.jvm.lab.LabArgs;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 场景6 的「被观察对象」：一个故意漏内存、并且可以被修好的进程。
 *
 * <p>为什么它不是一个跑完就退出的 main：内存泄漏要看得见的东西是
 * <em>同一个进程在不同时刻</em>的堆内容。进程一退出，直方图、堆转储、{@code jstat} 全都无从取。
 * 所以本类按「阶段」走：漏一批 → 打印 {@code READY phase=1} 并阻塞等 stdin 指令 →
 * 再漏一批 → {@code READY phase=2} → 等 {@code EXIT}。
 * 中间父进程会来敲 {@code jcmd <pid> GC.class_histogram}，见
 * {@link LeakDetectLab}。</p>
 *
 * <p>四种最常见的泄漏，都能用 {@code --case} 选：
 * <ul>
 *   <li>{@code staticCollection}：static 集合只进不出。真实代码里通常是
 *       {@code static Map<String, Session>}、监听器注册表、本地缓存；
 *       {@code --fix=true} 时改成立刻释放（处理完就清空），对照直方图里 {@code [B} 的字节数；</li>
 *   <li>{@code threadLocal}：线程池 + {@code ThreadLocal<List<byte[]>>}，任务只往里塞不 {@code remove()}。
 *       线程不死，值就一直活着——这是 Web 服务最常见的「查不到谁泄漏」的一类；
 *       {@code --fix=true} 时在 finally 里 {@code remove()}；</li>
 *   <li>{@code unboundedCache}：容量无上限的缓存。{@code --fix=true} 换成
 *       {@code LinkedHashMap} 的 LRU（{@code --cacheLimit} 控制上限）；</li>
 *   <li>{@code intern}：{@code String.intern()} 造键。<strong>本案例实测推翻了它的「泄漏」名声</strong>：
 *       JDK 21 的 intern 表条目是弱引用，20 万条在一次 Full GC 后掉回几十条，可达堆不涨；
 *       留在原生内存里的只有桶数组。之所以还留着这个 case，是因为「intern 会泄漏」这句话
 *       在面试和旧文章里出现得太频繁，需要一个能对证的数字。</li>
 * </ul></p>
 *
 * <p>坑（都是实测出来的）：
 * <ul>
 *   <li>{@code --fix} 之后不能一个字节都不涨：如果修好以后直方图完全不动，
 *       说明负载本身没跑（比如对象被 JIT 消掉了），那种「修复前后一样干净」是假象。
 *       所以 READY 行同时报 {@code processedKb}（两种版本必须相同，证明活儿一样多）和
 *       {@code heldKb}（此刻还抓住不放的量，修好版应当不涨），供父进程核对；</li>
 *   <li>线程池必须留到进程结束再关：中途 {@code shutdown()} 会把带着 ThreadLocal 的线程回收，
 *       泄漏当场消失，实验就变成「什么都没漏」；</li>
 *   <li>读 stdin 一定要用带超时的等待（在父进程侧），子进程这里可以一直等——
 *       它是守护进程，被父进程 {@code destroyForcibly()} 兜底。</li>
 * </ul></p>
 */
public final class LeakLab {

    /**
     * stdin 协议里唯一有语义的一行。
     *
     * <p>其它任何一行（父进程发的是 {@code GO}）都只表示「进入下一阶段」——
     * 这样父进程不需要知道子进程一共几个阶段，只要看到 {@code EXIT} 对方就一定会退出。
     * 之前 {@code EXIT} 只是「碰巧是最后一行」，在只跑一个阶段的取证路径里会让子进程
     * 继续跑第二阶段然后卡在 stdin 上，父进程等不到退出只能强杀。</p>
     */
    private static final String EXIT_COMMAND = "EXIT";

    /** case 1：静态集合，进程生命周期内只增不减。 */
    private static final List<byte[]> STATIC_LEAK = new ArrayList<>();

    /** case 3 的「修好版本」用的 LRU 上限；0 表示不设限（即泄漏版本）。 */
    private int cacheLimit;

    /** case 3：缓存本体。用 Map 接口持有，方便在 HashMap / LinkedHashMap 之间切。 */
    private Map<String, byte[]> cache = new HashMap<>();

    /** case 2：线程上下文，每个线程一份 List，任务只往里加。 */
    private final ThreadLocal<List<byte[]>> requestContext = ThreadLocal.withInitial(ArrayList::new);

    /** 单线程任务用的线程池；池大小决定「有几个线程在替我们攒垃圾」。 */
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    /** 是否使用修复后的写法；false=泄漏版，true=修好版。 */
    private final boolean fixed;

    /**
     * 累计<strong>处理</strong>过的字节（两种版本应当相同）：用来证明活儿真的干了。
     *
     * <p>为什么不能只报一个数：修好版照样分配同样多的对象，只是不再留住引用。
     * 只看「分配量」会得出「修复前后一样」的错误结论，只看「留存量」又分不清
     * 「没干活」和「干了但放掉了」。</p>
     */
    private final AtomicLong processedBytes = new AtomicLong();

    /** 此刻仍被引用抓住的字节；修好版这一项应该基本不涨。 */
    private final AtomicLong heldBytes = new AtomicLong();

    /** 已处理条目数，两个阶段各自累加，父进程用来核对工作量。 */
    private long items;

    /**
     * 跨阶段递增的条目序号，用作缓存 key 和 intern 键。
     *
     * <p>为什么不能直接用循环下标 {@code i}：两个阶段的 {@code i} 都从 0 开始，
     * key 会完全重复。对 {@code HashMap} 来说重复 key 只是覆盖 value（size 不涨），
     * 对 intern 表来说重复键第二次不再插入——两种情况都会让阶段 2「看起来没漏」，
     * 而那纯属实验写错了。真实服务每轮请求带来的是新 id，所以序号必须一直往前推。</p>
     */
    private long globalIndex;

    /**
     * 漏一轮 → 打印 READY → 等父进程发 GO → 再漏一轮 → 打印 READY → 等命令。
     *
     * <p>用法（正常情况由 {@link LeakDetectLab} 起，不建议手敲）：
     * {@code java -Xms256m -Xmx256m -cp demo-jvm/target/classes
     * com.example.demo.jvm.leak.LeakLab --case=staticCollection --fix=false
     * --items=200 --chunkKb=64 --cacheLimit=64}。</p>
     *
     * <p>协议：stdin 每行一个命令，只有 {@code GO}（继续下一阶段）和 {@code EXIT}（立刻收工）两种。
     * 之所以要「等命令而不是睡固定时长」，是因为父进程要在两个时刻之间拍 {@code jcmd} 直方图，
     * 睡眠式的同步会让第一张图拍到半成品。手敲时可以喂两行：{@code printf 'GO\nEXIT\n' | java ...}。</p>
     */
    public static void main(String[] args) throws Exception {
        LabArgs parsed = LabArgs.parse(args);
        String caseName = parsed.string("case", "staticCollection");
        int itemsPerPhase = parsed.number("items", 200);
        int chunkKb = parsed.number("chunkKb", 64);
        boolean fixed = parsed.bool("fix", false);
        int cacheLimit = parsed.number("cacheLimit", 64);

        LeakLab lab = new LeakLab(fixed, cacheLimit);
        System.out.println("# lab=leak-child case=" + caseName + " fix=" + fixed
                + " pid=" + ProcessHandle.current().pid()
                + " itemsPerPhase=" + itemsPerPhase + " chunkKb=" + chunkKb);
        System.out.println("VM " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));

        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        // 阶段 1 先漏一轮，父进程此时拍第一张直方图当基线；阶段 2 再漏同样多的一轮。
        // 用循环而不是把两段代码抄两遍：关键是「收到 EXIT 就立刻收工」这条规则只能写一次，
        // 写两遍就会出现「父进程想提前结束、子进程却还以为可以继续」的协议错位
        for (int phase = 1; phase <= 2; phase++) {
            lab.runPhase(caseName, itemsPerPhase, chunkKb);
            lab.ready(phase);
            String command = stdin.readLine();
            if (EXIT_COMMAND.equalsIgnoreCase(command)) {
                System.out.println("EXITED early phase=" + phase);
                break;
            }
        }

        lab.pool.shutdownNow();
        lab.pool.awaitTermination(5, TimeUnit.SECONDS);
        System.out.println("DONE processedKb=" + (lab.processedBytes.get() / 1024)
                + " heldKb=" + (lab.heldBytes.get() / 1024) + " items=" + lab.items);
    }

    /**
     * 打一行「阶段就绪」给父进程。
     *
     * <p>{@code flush()} 不是多余的：System.out 在重定向到管道时是带缓冲的，
     * 不刷新的话父进程会一直等不到 READY，看起来像子进程卡死。</p>
     *
     * <p>两个自报数字各有用途：{@code processedKb} 让父进程确认「两种版本干的活一样多」，
     * {@code heldKb} 给出「子进程自己认为还抓住多少」，可以和 jcmd 直方图的可达总量对照——
     * 对得上才说明直方图测到的是同一件事。</p>
     */
    private void ready(int phase) {
        System.out.println("READY phase=" + phase
                + " processedKb=" + (processedBytes.get() / 1024)
                + " heldKb=" + (heldBytes.get() / 1024)
                + " items=" + items);
        System.out.flush();
    }

    private LeakLab(boolean fixed, int cacheLimit) {
        this.fixed = fixed;
        this.cacheLimit = cacheLimit;
        if (fixed) {
            // 修好版：换成有上限的 LRU。这里必须在构造期换掉，
            // 否则阶段 1 用无界 Map、阶段 2 用 LRU，前后就不是同一个数据结构了
            cache = new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > cacheLimit;
                }
            };
        }
    }

    /**
     * 跑一个阶段。
     *
     * @param caseName     哪种泄漏
     * @param itemCount    本阶段处理多少条
     * @param chunkKb      每条数据多大，KB
     */
    private void runPhase(String caseName, int itemCount, int chunkKb) throws InterruptedException {
        switch (caseName) {
            case "staticCollection" -> staticCollection(itemCount, chunkKb);
            case "threadLocal" -> threadLocal(itemCount, chunkKb);
            case "unboundedCache" -> unboundedCache(itemCount, chunkKb);
            case "intern" -> intern(itemCount);
            default -> throw new IllegalArgumentException("未知 --case=" + caseName
                    + "，可选：staticCollection / threadLocal / unboundedCache / intern");
        }
    }

    /**
     * 静态集合：修好版把「留着」换成「用完就走」。
     *
     * <p>注意修好版仍然要分配同样多的对象——区别只在<strong>引用留不留</strong>。
     * 如果修好版连分配都省了，直方图对比就是在比「做了不同的事」，没有说服力。</p>
     */
    private void staticCollection(int itemCount, int chunkKb) {
        for (int i = 0; i < itemCount; i++) {
            byte[] payload = new byte[chunkKb * 1024];
            payload[0] = (byte) i;
            items++;
            processedBytes.addAndGet(payload.length);
            if (fixed) {
                // 修好版：引用只活在局部变量里，处理完就断开。
                // 但要让它「逃逸」一次——否则 JIT 的标量替换可能把整个数组消掉，
                // 实验就变成「修复后一个字节都没分配」，那是假象不是结论
                Deque<byte[]> scoped = new ArrayDeque<>();
                scoped.add(payload);
                scoped.poll();
            } else {
                STATIC_LEAK.add(payload);
                heldBytes.addAndGet(payload.length);
            }
        }
    }

    /**
     * ThreadLocal：模拟「请求上下文」在线程池上被反复追加。
     *
     * <p>关键点：泄漏的不是 ThreadLocal 对象本身，而是<strong>活着的线程持有的值</strong>。
     * Tomcat/Netty 这类容器线程长期不退出，所以上下文里的东西永远回收不掉。</p>
     */
    private void threadLocal(int itemCount, int chunkKb) throws InterruptedException {
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < itemCount; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                byte[] payload = new byte[chunkKb * 1024];
                payload[0] = (byte) index;
                requestContext.get().add(payload);
                processedBytes.addAndGet(payload.length);
                if (fixed) {
                    // 修好版：任务结束前清掉本线程的上下文，这才是「线程池 + ThreadLocal」的正确用法
                    requestContext.remove();
                } else {
                    heldBytes.addAndGet(payload.length);
                }
            }));
        }
        for (var future : futures) {
            // 每个任务都等完：不让阶段 2 的分配和阶段 1 的收尾混在一起，直方图才对得上
            awaitQuietly(future);
        }
        items += itemCount;
    }

    private static void awaitQuietly(java.util.concurrent.Future<?> future) {
        try {
            future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("线程池任务失败：" + e, e);
        }
    }

    /** 无界缓存 vs LRU 缓存。 */
    private void unboundedCache(int itemCount, int chunkKb) {
        for (int i = 0; i < itemCount; i++) {
            // key 每次都不同：真实场景是「按用户/订单 id 缓存」，键空间无限，缓存就无限
            cache.put("cache-key-" + globalIndex++, new byte[chunkKb * 1024]);
            items++;
            processedBytes.addAndGet(chunkKb * 1024L);
            // 留存量直接问缓存自己：修好版（LRU）的 size() 会被压在 cacheLimit 上不再增长，
            // 这正是「上限」在直方图里应有的样子
            heldBytes.set(cache.size() * (long) chunkKb * 1024L);
        }
    }

    /**
     * intern：把每次现算出来的字符串塞进全局字符串表。
     *
     * <p>修好版只是「不 intern」。但<strong>本案例的实测结论和常见说法相反</strong>：
     * JDK 21 上 intern 表的条目是弱引用，只要字符串没被别处抓住，一次 Full GC 就全清掉，
     * 可达堆总量不涨（本机 20 万条 → GC 后剩几十条）。所以「intern 一定泄漏」是
     * JDK 6 时代 PermGen 的旧结论，今天真正回不去的只有桶数组那段原生内存。
     * 证据在 {@link LeakDetectLab#printStringTableProof}。</p>
     */
    private void intern(int itemCount) {
        for (int i = 0; i < itemCount; i++) {
            long id = globalIndex++;
            String raw = "leak-key-" + id + "-" + (id * 31);
            // 必须在分支里决定要不要 intern：如果先无条件 intern 再把 key 换成 raw，
            // 那第一版字符串已经进了全局表，「修好版」照样在漏——这是本案例最容易写错的一行
            String key = fixed ? raw : raw.intern();
            if (!fixed) {
                // 粗算 intern 住的成本：String 头 24B + byte[] 头 16B + 字符数
                // （JDK 9+ 的 LATIN1 字符串一字符一字节）。这里只求「量级对得上」，
                // 真实数字以 jcmd 直方图为准——单条才几十字节，必须靠 items 放大才看得见
                heldBytes.addAndGet(40L + key.length());
            }
            processedBytes.addAndGet(40L + key.length());
            // 用一下 key，避免编译器把整段当死代码消掉
            if (key.isEmpty()) {
                System.out.println("impossible");
            }
            items++;
        }
    }

}
