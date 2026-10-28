package com.example.demo.redis.lock;

import java.util.List;

/**
 * 手写锁 / Redisson 锁的演示结果。所有时间点都是相对开始时刻的毫秒数。
 */
public final class LockResults {

    private LockResults() {
    }

    public record Event(long atMillis, String actor, String action, boolean success, String note) {
    }

    public record TtlSample(long atMillis, long ttlMillis) {
    }

    public record SimpleLockResult(String mode, String lockKey, long ttlMillis, long holdMillis,
                                   boolean overlap, boolean renewed,
                                   List<Event> events, List<TtlSample> ttlSamples) {
    }

    public record RedissonLockResult(String mode, String lockKey, long holdSeconds, Long leaseSeconds,
                                     boolean overlap, boolean watchdogRenewed,
                                     List<Event> events, List<TtlSample> ttlSamples) {
    }

    public record CounterResult(boolean locked, int threads, int iterations,
                                long expected, long actual, long lost, long elapsedMillis) {
    }
}
