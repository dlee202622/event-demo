package com.example.demo.concurrency.lock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 场景 6 的测试：编排版丢更新必须精确；三种安全写法的结果必须等于理论值。
 */
class CounterDemoTest {

    private final CounterDemo demo = new CounterDemo();

    @Test
    void orchestratedLostUpdatesAreExact() {
        CounterDemo.LostUpdateResult result = demo.lostUpdates(4, 500);

        assertThat(result.orchestrated()).isTrue();
        assertThat(result.expected()).isEqualTo(2000);
        assertThat(result.actual()).isEqualTo(500);
        assertThat(result.lost()).isEqualTo(1500);
    }

    @Test
    void safeCountersAreExact() {
        CounterDemo.CompareResult result = demo.compare(4, 5000, 2);

        assertThat(result.modes()).extracting(CounterDemo.ModeResult::mode)
                .containsExactly("none", "synchronized", "atomic", "longadder");
        assertThat(result.modes())
                .filteredOn(mode -> !"none".equals(mode.mode()))
                .allSatisfy(mode -> {
                    assertThat(mode.exact()).isTrue();
                    // 每种写法每轮都必须等于 4 × 5000
                    assertThat(mode.actuals()).allMatch(actual -> actual == 20000L);
                });
    }
}
