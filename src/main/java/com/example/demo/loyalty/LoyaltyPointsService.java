package com.example.demo.loyalty;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 积分服务（又一个独立限界上下文）。
 *
 * <p>它只订阅 OrderPaidEvent —— 一个事件驱动的最佳实践：
 * <b>订阅者只关心自己需要的最小事件</b>，而不是订阅「订单的一切变化」。
 * 这样上游新增事件时不会波及下游。</p>
 */
@Service
public class LoyaltyPointsService {

    private final Map<String, Integer> points = new ConcurrentHashMap<>();

    /** 发放积分。 */
    public int earn(String customerId, int pointsToEarn) {
        int total = points.merge(customerId, pointsToEarn, Integer::sum);
        return total;
    }

    public int pointsOf(String customerId) {
        return points.getOrDefault(customerId, 0);
    }

    public Map<String, Integer> snapshot() {
        return new TreeMap<>(points);
    }

    /** 仅测试/演示用。 */
    public void reset() {
        points.clear();
    }
}
