package com.example.demo.concurrency.lock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 场景 5 的测试：交叉顺序必须检出并清理死锁；固定顺序必须正常完成。
 */
class DeadlockDemoTest {

    private final DeadlockDemo demo = new DeadlockDemo();

    @Test
    void crossOrderDeadlocksAndRecovers() {
        DeadlockDemo.Result result = demo.run(false, 100, 1500);

        assertThat(result.deadlockDetected()).isTrue();
        assertThat(result.deadlockedThreads()).hasSize(2);
        assertThat(result.deadlockedThreads()).allSatisfy(thread -> {
            assertThat(thread.waitingOn()).isNotBlank();
            assertThat(thread.lockOwner()).isNotBlank();
        });
        assertThat(result.allThreadsFinished()).isTrue();
        assertThat(result.cleanedUp()).isTrue();
        assertThat(result.interrupted()).isTrue();
    }

    @Test
    void fixedOrderAvoidsDeadlock() {
        DeadlockDemo.Result result = demo.run(true, 100, 1500);

        assertThat(result.deadlockDetected()).isFalse();
        assertThat(result.allThreadsFinished()).isTrue();
        assertThat(result.cleanedUp()).isTrue();
        assertThat(result.interrupted()).isFalse();
    }
}
