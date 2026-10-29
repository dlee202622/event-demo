package com.example.demo.concurrency.lock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 进阶场景 2 的测试：三种计数写法都必须精确；分段版本还要满足
 * Cell 数量规范化、分布覆盖全部 Cell、各 Cell 计数之和等于总数。
 */
class StripedCounterDemoTest {

    private final StripedCounterDemo demo = new StripedCounterDemo();

    @Test
    void allModesAreExactAndStripedDistributionAddsUp() {
        StripedCounterDemo.Result result = demo.compare(4, 1_000, 4, 1);

        assertThat(result.actualCells()).isEqualTo(4);
        assertThat(result.modes()).extracting(StripedCounterDemo.ModeResult::mode)
                .containsExactly("atomic", "striped", "longadder");
        assertThat(result.modes()).allSatisfy(mode -> {
            assertThat(mode.exact()).isTrue();
            assertThat(mode.actuals()).allMatch(actual -> actual == 4_000L);
        });

        StripedCounterDemo.ModeResult striped = result.modes().get(1);
        assertThat(striped.cellDistribution()).hasSize(4);
        assertThat(striped.cellDistribution()).anyMatch(count -> count > 0);
        assertThat(striped.cellDistribution().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(4_000L);
    }

    @Test
    void singleCellIsTheDegenerateCase() {
        StripedCounterDemo.Result result = demo.compare(4, 2_000, 1, 1);

        assertThat(result.actualCells()).isEqualTo(1);
        StripedCounterDemo.ModeResult striped = result.modes().get(1);
        assertThat(striped.exact()).isTrue();
        assertThat(striped.cellDistribution()).containsExactly(8_000L);
    }

    @Test
    void cellCountRoundsUpToPowerOfTwo() {
        assertThat(StripedCounterDemo.StripedCounter.normalizeCells(1)).isEqualTo(1);
        assertThat(StripedCounterDemo.StripedCounter.normalizeCells(3)).isEqualTo(4);
        assertThat(StripedCounterDemo.StripedCounter.normalizeCells(16)).isEqualTo(16);
        assertThat(StripedCounterDemo.StripedCounter.normalizeCells(17)).isEqualTo(32);
    }
}
