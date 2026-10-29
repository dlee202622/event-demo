package com.example.demo.concurrency.lock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 场景 2 的测试：重入计数、AQS 队列长度、非持有者 unlock 的异常。
 */
class ReentrantLockDemoTest {

    private final ReentrantLockDemo demo = new ReentrantLockDemo();

    @Test
    void reentrancyQueueAndMisuse() {
        ReentrantLockDemo.Result result = demo.run(5, 100);

        // 三次 lock 后 holdCount 依次 1/2/3，三次 unlock 后依次 2/1/0
        assertThat(result.lockSteps())
                .extracting(step -> step.action() + step.holdCount())
                .containsExactly("lock1", "lock2", "lock3", "unlock2", "unlock1", "unlock0");
        assertThat(result.maxQueueLength()).isEqualTo(5);
        assertThat(result.acquisitionOrder()).hasSize(5);
        assertThat(result.allWaitersAcquired()).isTrue();
        // 非持有者 unlock 必须被 AQS 拒绝
        assertThat(result.misuse().exception()).isEqualTo("IllegalMonitorStateException");
        assertThat(result.lockFreeAtEnd()).isTrue();
    }
}
