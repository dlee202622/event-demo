package com.example.demo.jvm.lab;

/**
 * 场景2 的样本类：每个方法只为说明一条局部变量槽规则，方法体故意写到「刚好够用」。
 *
 * <p>为什么需要单独一个样本类：局部变量槽是编译期决定的，运行期看不到，
 * 只能靠 {@code javap -v} 读字节码里的 {@code max_locals}。
 * 与其让读者去翻一个真实业务类（槽位复用会糊成一团），不如造一组最小样本，
 * 每个样本对应一个可以背下来的规则。</p>
 *
 * <p>配套的执行者是 {@link LocalSlotLab}，它把本类的 {@code max_stack / max_locals /
 * LocalVariableTable} 读出来，和「按描述符手算的槽数」对比。</p>
 *
 * <p>三条规则（本类逐条覆盖）：
 * <ul>
 *   <li>实例方法的槽 0 永远是 {@code this}，静态方法没有；</li>
 *   <li>{@code long} 和 {@code double} 各占两槽，其余基本类型和引用占一槽；</li>
 *   <li>{@code max_locals} 是「同时存活的槽数峰值」，不是变量个数——
 *       顺序的两个代码块可以复用同一批槽，生命周期重叠的不行。</li>
 * </ul></p>
 *
 * <p>坑：本类的字段（如 {@code counter}）<strong>不在</strong>栈帧的局部变量表里，
 * 它们跟着对象在堆上；读者容易把「成员变量」和「局部变量槽」混成一件事，
 * 所以这里放一个字段，配合 {@link #readsField()} 让 javap 证明它不占槽。</p>
 */
public class LocalSlotSample {

    /** 实例字段：属于堆上的对象，不占栈帧槽位。 */
    int counter = 7;

    /** 静态方法、无参：{@code max_locals = 0}，一个槽都不用。 */
    public static int staticNoArgs() {
        return 1;
    }

    /** 实例方法、无参：{@code max_locals = 1}，就是 {@code this}。 */
    public int instanceNoArgs() {
        return 1;
    }

    /** 单个 {@code long} 参数：占 0 和 1 两槽，所以 {@code max_locals = 2}。 */
    public static long oneLong(long value) {
        return value;
    }

    /** {@code int} 在前、{@code long} 在后：槽 0 是 a，槽 1、2 是 b，共 3 槽。 */
    public static long intThenLong(int a, long b) {
        return a + b;
    }

    /** {@code long} 在前、{@code int} 在后：槽 0、1 是 b，槽 2 是 a——顺序影响槽号，不影响总数。 */
    public static long longThenInt(long b, int a) {
        return a + b;
    }

    /**
     * 两个代码块各声明一个变量，生命周期不重叠：javac 让 {@code first} 和 {@code second}
     * 用同一个槽 1，所以 {@code max_locals} 是 2（参数 flag 占槽 0 + 复用的一槽），
     * 而不是「3 个变量 = 3 槽」。LocalVariableTable 里两者的区间不相交，就是复用的证据。
     */
    public static int reusesSlotsInSequentialBlocks(boolean flag) {
        if (flag) {
            int first = 1;
            return first;
        }
        int second = 2;
        return second;
    }

    /**
     * 三个变量生命周期互相重叠（{@code c} 要读 {@code a} 和 {@code b}）：
     * 槽位不能复用，{@code max_locals} 涨到参数 1 + 局部 3 = 4。
     */
    public static int keepsLocalsAlive(int seed) {
        int a = seed + 1;
        int b = seed + 2;
        int c = a + b;
        return a + b + c;
    }

    /** 读实例字段：字节码是 {@code aload_0} + {@code getfield}，字段值不进局部变量表。 */
    public int readsField() {
        return counter;
    }

    /** 引用类型参数占一槽（存的是引用，对象本身在堆上）。 */
    public static int referenceArg(String name) {
        return name.length();
    }

    /** 数组参数占一槽，无论几维。 */
    public static int arrayArg(int[][] matrix) {
        return matrix.length;
    }

    /** 递归：每一层调用都要一份完整的槽，这就是 {@link StackDepthLab} 里栈深的来源。 */
    public static int recurse(int depth) {
        if (depth <= 0) {
            return 0;
        }
        return 1 + recurse(depth - 1);
    }
}
