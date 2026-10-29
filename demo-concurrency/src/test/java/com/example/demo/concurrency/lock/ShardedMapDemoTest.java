package com.example.demo.concurrency.lock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 进阶场景 3 的测试：三种模式的校验和都必须精确；全局锁与热点 key
 * 的临界区并发度必须为 1；分片模式的分布数组长度必须等于分片数。
 */
class ShardedMapDemoTest {

    private final ShardedMapDemo demo = new ShardedMapDemo();

    @Test
    void allModesKeepMapConsistent() {
        ShardedMapDemo.Result result = demo.run(4, 16, 40, 8, 50);

        assertThat(result.modes()).extracting(ShardedMapDemo.ModeResult::mode)
                .containsExactly("global", "sharded", "hotkey");
        assertThat(result.modes()).allSatisfy(mode -> {
            assertThat(mode.exact()).isTrue();
            assertThat(mode.actualSum()).isEqualTo(160);
            assertThat(mode.shardDistribution()).hasSize(mode.shards());
        });

        assertThat(result.modes().get(0).maxConcurrent()).isEqualTo(1);
        assertThat(result.modes().get(1).maxConcurrent()).isGreaterThanOrEqualTo(1);
        assertThat(result.modes().get(2).maxConcurrent()).isEqualTo(1);
        // 热点 key 模式所有操作都落在同一把锁上，其他分片应为 0
        long usedShards = result.modes().get(2).shardDistribution().stream()
                .filter(count -> count > 0).count();
        assertThat(usedShards).isEqualTo(1);
    }

    @Test
    void hotkeyMapHasOnlyOneKey() {
        ShardedMapDemo.Result result = demo.run(4, 16, 20, 4, 20);

        assertThat(result.modes().get(0).mapSize()).isEqualTo(16);
        assertThat(result.modes().get(2).mapSize()).isEqualTo(1);
    }

    @Test
    void repeatedShardedRunsStayConsistent() {
        // 共用一张 HashMap 的旧实现会在这里偶发 mapSize 少 1：不同分片并发改表结构
        for (int i = 0; i < 5; i++) {
            ShardedMapDemo.ModeResult sharded = demo.run(8, 64, 100, 16, 10).modes().get(1);

            assertThat(sharded.exact()).isTrue();
            assertThat(sharded.actualSum()).isEqualTo(800);
            assertThat(sharded.mapSize()).isEqualTo(64);
        }
    }
}
