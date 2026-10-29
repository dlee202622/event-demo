package com.example.demo.concurrency.lock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 进阶场景 1 的测试：无看门狗必须能观察到过期接管，有看门狗必须阻止接管，
 * 且两种情况都要验证“释放后不再续期”和“迟到 release 被忽略”。
 */
class WatchdogDemoTest {

    private final WatchdogDemo demo = new WatchdogDemo();

    @Test
    void withoutWatchdogLeaseExpiresAndAnotherThreadTakesOver() {
        WatchdogDemo.WatchdogResult result = demo.run(300, 1_200, false);

        assertThat(result.overlap()).isTrue();
        assertThat(result.renewCount()).isZero();
        assertThat(result.renewedAfterRelease()).isFalse();
        // B 接管后 A 才结束，A 的 release 不能释放 B 的锁
        assertThat(result.staleReleaseIgnored()).isTrue();
        assertThat(result.bAcquiredAtMillis()).isLessThan(result.aReleasedAtMillis());
        assertThat(result.ownerAtEnd()).isNull();
        assertThat(result.timeline()).extracting(event -> event.action())
                .contains("acquire", "release-ignored");
    }

    @Test
    void watchdogRenewsUntilReleaseAndStopsAfterwards() {
        WatchdogDemo.WatchdogResult result = demo.run(300, 1_200, true);

        assertThat(result.overlap()).isFalse();
        assertThat(result.renewCount()).isGreaterThan(0);
        assertThat(result.renewedAfterRelease()).isFalse();
        assertThat(result.staleReleaseIgnored()).isFalse();
        assertThat(result.bAcquiredAtMillis()).isGreaterThanOrEqualTo(result.aReleasedAtMillis());
        assertThat(result.ownerAtEnd()).isNull();
        assertThat(result.timeline()).extracting(event -> event.action())
                .contains("acquire", "renew", "release");
    }
}
