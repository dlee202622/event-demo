package com.example.demo.jvm.controller;

import com.example.demo.jvm.area.AreaSnapshot;
import com.example.demo.jvm.area.HeapHolder;
import com.example.demo.jvm.area.MemoryAreaService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行时数据区观测与堆的手动分配。
 *
 * <p>接口分两组：</p>
 * <ul>
 *   <li>{@code /areas|/pools|/gc|/threads|/self}：只读观测，分别对应「教材六个区」
 *       「HotSpot 内存池原样」「GC 累计统计」「线程与栈」「进程身份」；</li>
 *   <li>{@code /allocate|/allocate/clear|/hold}：受控分配按钮，用来制造堆变化。</li>
 * </ul>
 *
 * <p>所有接口都是毫秒级返回，没有等待逻辑；唯一可能慢的是内部读取 NMT
 * （约 10 ms 量级）。{@code /allocate} 有上限保护，见 {@link HeapHolder}。</p>
 */
@RestController
@RequestMapping("/api/jvm")
public class JvmAreaController {

    private final MemoryAreaService areas;
    private final HeapHolder heapHolder;

    /**
     * @param areas       只读观测的数据源，本类所有 GET 都转发到它
     * @param heapHolder  带保护的堆持有器：本类是唯一会<em>改</em> JVM 状态的控制器，
     *                    上限与「释放引用不等于回收」的规则都收在它那里，控制器不复述
     */
    public JvmAreaController(MemoryAreaService areas, HeapHolder heapHolder) {
        this.areas = areas;
        this.heapHolder = heapHolder;
    }

    /**
     * 完整快照：教材分区 + 内存池 + GC + 线程 + 直接内存 + NMT + 关键参数。
     * 学习路径从这里开始，一张响应能对照上内存分区图。
     */
    @GetMapping("/areas")
    public AreaSnapshot areas() {
        return areas.snapshot();
    }

    /** 只看内存池，方便和 {@code -XX} 参数、{@code jcmd GC.heap_info} 逐项对照。 */
    @GetMapping("/pools")
    public List<AreaSnapshot.Pool> pools() {
        return areas.poolViews();
    }

    /**
     * GC 累计统计。
     *
     * <p>注意 collectionCount 是进程启动至今的累计值，看一次没有意义；
     * 要用「分配前后各读一次取差值」的方式观察（文档里有对应的两条 curl）。</p>
     */
    @GetMapping("/gc")
    public List<AreaSnapshot.Collector> gc() {
        return areas.collectorViews();
    }

    /** 线程数与栈估算：栈区总用量 = ThreadStackSize × 活跃线程数。 */
    @GetMapping("/threads")
    public AreaSnapshot.Threads threads() {
        return areas.threadView();
    }

    /** 进程信息：pid、GC 算法、物理内存、启动参数——pid 用来接 jcmd / jstat 系列命令。 */
    @GetMapping("/self")
    public AreaSnapshot.Process self() {
        return areas.snapshot().process();
    }

    /** 直接内存单独给出，方便解释「堆没满但进程被 OOM 打死」这类问题。 */
    @GetMapping("/direct")
    public AreaSnapshot.DirectMemory direct() {
        return areas.directMemory();
    }

    /** 当前保留在堆上的块数与总量。 */
    @GetMapping("/hold")
    public HeapHolder.HeldStats hold() {
        return heapHolder.stats();
    }

    /**
     * 分配一块内存。
     *
     * @param mb     大小，单位 MB，默认 8，最大见 {@code demo.jvm.max-allocate-mb}
     * @param retain 默认 true 保留强引用（观察老年代上涨）；
     *               false 时对象没有引用，下一次 Young GC 就回收（观察「涨了又掉」）
     */
    @PostMapping("/allocate")
    public HeapHolder.AllocationResult allocate(@RequestParam(defaultValue = "8") int mb,
                                                @RequestParam(defaultValue = "true") boolean retain) {
        return heapHolder.allocate(mb, retain);
    }

    /**
     * 丢弃全部保留引用。
     *
     * <p>刻意不顺手触发 GC：读者需要自己调 {@code POST /api/jvm/gc}，
     * 才能看到「释放引用 → 下一次 GC 才真正回收」这个顺序。</p>
     */
    @PostMapping("/allocate/clear")
    public HeapHolder.HeldStats clear() {
        return heapHolder.clear();
    }
}
