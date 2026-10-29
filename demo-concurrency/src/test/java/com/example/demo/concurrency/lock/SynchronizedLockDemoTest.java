package com.example.demo.concurrency.lock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.example.demo.concurrency.support.Observations.ThreadSnapshot;

/**
 * 场景 1 的测试：加锁必须串行，不加锁必须重叠。
 */
class SynchronizedLockDemoTest {

    private final SynchronizedLockDemo demo = new SynchronizedLockDemo();

    @Test
    void lockedSerializesAndShowsBlockedOwner() {
        SynchronizedLockDemo.Result result = demo.run(true, 400);

        assertThat(result.maxConcurrent()).isEqualTo(1);
        assertThat(result.spans()).hasSize(2);
        // 采样时一个线程在临界区里 sleep，另一个卡在 monitorenter，JMX 应给出 BLOCKED + 持有者
        assertThat(result.snapshots()).anySatisfy(snapshot -> {
            assertThat(snapshot.state()).isEqualTo("BLOCKED");
            assertThat(snapshot.lockOwner()).isNotBlank();
        });
    }

    @Test
    void unlockedOverlaps() {
        SynchronizedLockDemo.Result result = demo.run(false, 400);

        assertThat(result.maxConcurrent()).isEqualTo(2);
        // 没有 monitor 参与，采样时两个线程都应在 sleep，lockOwner 为空
        assertThat(result.snapshots()).allSatisfy(snapshot ->
                assertThat(snapshot.lockOwner()).isNull());
    }
}
