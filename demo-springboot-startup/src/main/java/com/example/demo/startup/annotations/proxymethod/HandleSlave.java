package com.example.demo.startup.annotations.proxymethod;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@code proxyBeanMethods} 实验的「叶子」Bean：每被 new 一次就记一笔账。
 *
 * <p>定位：要证明「lite 模式下 {@code @Bean} 方法之间是普通方法调用」，
 * 光比对象引用不够直观——读者更想看见「同一个 @Bean 方法到底被执行了几次」。
 * 于是这里按档位（full / lite）分别计数，接口把计数原样回显。</p>
 *
 * <p>关键实现：计数器是静态的、按 tier 分桶。静态是因为两个配置类共用这一个类型；
 * 分桶是因为主应用常驻一对、实验子应用（若将来复用）还会再产一对，
 * 混在一个计数器里就没法解释「为什么是 3」。</p>
 *
 * <p>坑：{@code serial} 是「本 tier 内的第几个实例，从 1 开始」，
 * 不是 Bean 名字。lite 模式下 {@code masterLite} 持有的那个 slave 的 serial 是 1，
 * 容器里真正注册的 {@code slaveLite} Bean 的 serial 是 2——顺序取决于
 * {@code @Bean} 方法的声明顺序与容器实例化顺序，别把它当成稳定的业务编号。</p>
 */
public class HandleSlave {

    /** tier → 已创建实例数；ConcurrentHashMap 是因为计数器可能在不同子应用的线程上自增 */
    private static final ConcurrentHashMap<String, AtomicInteger> COUNTERS = new ConcurrentHashMap<>();

    private final String tier;

    /** 本 tier 内的序号（1 起）：连续 new 两次就是 1、2 */
    private final int serial;

    public HandleSlave(String tier) {
        this.tier = tier;
        // computeIfAbsent + incrementAndGet：同一 tier 的两次创建必须落到同一个计数器上
        this.serial = COUNTERS.computeIfAbsent(tier, key -> new AtomicInteger()).incrementAndGet();
    }

    /** 该 tier 到现在为止一共 new 出多少个 HandleSlave（0 表示该档从未被触发） */
    public static int createdCount(String tier) {
        AtomicInteger counter = COUNTERS.get(tier);
        return counter == null ? 0 : counter.get();
    }

    public String tier() {
        return tier;
    }

    public int serial() {
        return serial;
    }

    /** 供接口列出「目前出现过哪些 tier」，方便读者核对计数是否被别的实验污染 */
    public static List<String> knownTiers() {
        return List.copyOf(COUNTERS.keySet());
    }
}
