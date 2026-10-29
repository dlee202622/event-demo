package com.example.demo.startup.annotations.proxymethod;

/**
 * {@code proxyBeanMethods} 实验的「聚合」Bean：它唯一的价值是记住
 * 自己当初拿到的那个 {@link HandleSlave} 的身份哈希。
 *
 * <p>定位：full 与 lite 的唯一区别就在这行构造参数上——
 * {@code new HandleMaster("full", slaveFull())} 里的 {@code slaveFull()}
 * 在两种模式下一个是「被 CGLIB 拦截后返回容器单例」，一个是「真的又 new 了一个」。</p>
 *
 * <p>关键实现：不存对象引用而存 {@code identityHashCode}，是为了让 JSON 里
 * 一眼看得出差异（引用相等用布尔字段表达，哈希值同时给出「两个对象各自是谁」）。</p>
 *
 * <p>坑：{@code System.identityHashCode} 不是内存地址，也不保证唯一，
 * 教学里只用它证明「两个引用指向不同实例」；判断同一个实例请用 {@code ==}
 * （接口里的 {@code sameInstance} 字段就是这么算的）。</p>
 */
public class HandleMaster {

    private final String tier;

    /** 构造时传入的那个 slave：与容器里的 slave Bean 比较引用是否相同 */
    private final HandleSlave slave;

    /** slave 的 identityHashCode，便于在 JSON 里肉眼比对 */
    private final int slaveIdentity;

    public HandleMaster(String tier, HandleSlave slave) {
        this.tier = tier;
        this.slave = slave;
        this.slaveIdentity = System.identityHashCode(slave);
    }

    public String tier() {
        return tier;
    }

    public HandleSlave slave() {
        return slave;
    }

    public int slaveIdentity() {
        return slaveIdentity;
    }
}
