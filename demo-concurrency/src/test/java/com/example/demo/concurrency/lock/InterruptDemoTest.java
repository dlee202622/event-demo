package com.example.demo.concurrency.lock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 场景 4 的测试：可中断锁必须快速抛异常；synchronized 必须等锁释放后才进入。
 */
class InterruptDemoTest {

    private final InterruptDemo demo = new InterruptDemo();

    @Test
    void reentrantLockInterruptiblyThrows() {
        InterruptDemo.Result result = demo.run(InterruptDemo.Mode.REENTRANT, 800, 150);

        assertThat(result.exceptionThrown()).isTrue();
        assertThat(result.exceptionType()).isEqualTo("InterruptedException");
        assertThat(result.victimEntered()).isFalse();
        assertThat(result.interruptLatencyMillis()).isBetween(0L, 500L);
        assertThat(result.blockedSnapshot().state()).isEqualTo("WAITING");
        assertThat(result.blockedSnapshot().lockOwner()).isNotBlank();
        // InterruptedException 会清除中断标记
        assertThat(result.interruptFlagAfter()).isFalse();
    }

    @Test
    void synchronizedIgnoresInterruptUntilRelease() {
        InterruptDemo.Result result = demo.run(InterruptDemo.Mode.SYNCHRONIZED, 800, 150);

        assertThat(result.exceptionThrown()).isFalse();
        assertThat(result.victimEntered()).isTrue();
        // 中断发生在约 150ms，但进入临界区要等到持有者 800ms 后释放
        assertThat(result.enteredAtMillis()).isGreaterThanOrEqualTo(700);
        assertThat(result.blockedSnapshot().state()).isEqualTo("BLOCKED");
        assertThat(result.blockedSnapshot().lockOwner()).isNotBlank();
        // 中断标记没有被清除，只是没能打断 monitorenter
        assertThat(result.interruptFlagAfter()).isTrue();
    }
}
