package com.example.demo.concurrency.lock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 场景 3 的测试：公平锁保证 FIFO；非公平锁至少在一轮里能观察到插队。
 */
class FairnessDemoTest {

    private final FairnessDemo demo = new FairnessDemo();

    @Test
    void fairLockQueuesHolderAtTail() {
        FairnessDemo.Result result = demo.run(true, 6, 2);

        assertThat(result.bargedRounds()).isZero();
        assertThat(result.roundResults()).allSatisfy(round -> {
            assertThat(round.holderPosition()).isEqualTo(7);
            assertThat(round.holderBarged()).isFalse();
            assertThat(round.waiterOrderMismatches()).isZero();
            assertThat(round.acquisitionOrder()).hasSize(7);
            assertThat(round.acquisitionOrder().get(6)).isEqualTo("holder-reacquire");
            assertThat(round.maxQueueLength()).isEqualTo(6);
        });
    }

    @Test
    void nonFairLockBargesInAtLeastOneRound() {
        FairnessDemo.Result result = demo.run(false, 6, 5);

        assertThat(result.roundResults()).allSatisfy(round ->
                assertThat(round.acquisitionOrder()).hasSize(7));
        // 插队受调度影响，允许个别轮次没抢到；5 轮里至少应出现一次
        assertThat(result.bargedRounds()).isGreaterThanOrEqualTo(1);
    }
}
