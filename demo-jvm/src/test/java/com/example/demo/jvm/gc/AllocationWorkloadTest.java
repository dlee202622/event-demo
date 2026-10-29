package com.example.demo.jvm.gc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link AllocationWorkload} 的确定性测试——整个 GC 场景的可比性都建立在这上面。
 *
 * <p>为什么值得单独立一个测试：{@link GcCompareLab} 把同一个负载分别跑在 Serial、Parallel、
 * G1、ZGC 上，然后拿停顿时间和吞吐率做横向对比。只有「负载本身完全相同」这个前提成立，
 * 那些差异才归因于收集器而不是归因于随机数。校验和就是这个前提的证明；
 * 它一旦不确定，整张对比表都没有意义。</p>
 */
class AllocationWorkloadTest {

    @Test
    @DisplayName("同参数同种子：校验和与对象数逐次相同")
    void checksumIsDeterministic() {
        AllocationWorkload.Stats first = AllocationWorkload.run(2, 1, 1, 32, 42L);
        AllocationWorkload.Stats second = AllocationWorkload.run(2, 1, 1, 32, 42L);

        assertEquals(first.checksum(), second.checksum());
        assertEquals(first.objects(), second.objects());
        assertEquals(first.allocatedMb(), second.allocatedMb());
    }

    @Test
    @DisplayName("换种子必须换校验和：证明它真的在观测数据内容，不是个常量")
    void checksumReactsToSeed() {
        assertNotEquals(AllocationWorkload.run(2, 1, 1, 32, 42L).checksum(),
                AllocationWorkload.run(2, 1, 1, 32, 777L).checksum());
    }

    @Test
    @DisplayName("对象数与分配量的算法可以手算复核")
    void countsMatchTheDocumentedArithmetic() {
        // objectKb=32 时：perRound = 1MB / 32KB = 32，liveCount = 1MB / 32KB = 32
        // objects = liveCount + rounds × (perRound + 1) = 32 + 2×33 = 98
        // 每轮多出来的 1 个是「换掉存活集里一个元素」的那次分配，容易漏算
        AllocationWorkload.Stats stats = AllocationWorkload.run(2, 1, 1, 32, 42L);
        assertEquals(98, stats.objects());

        // allocatedMb 按「意图分配量」算，不是按 objects×32KB 反算：
        // 向下取整会让 32KB 的对象刚好整除，这里两者相等；换成 3KB 就会分叉
        assertEquals(3.0, stats.allocatedMb());
    }
}
