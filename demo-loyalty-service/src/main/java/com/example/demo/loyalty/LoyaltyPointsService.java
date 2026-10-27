package com.example.demo.loyalty;

import com.example.demo.loyalty.domain.PointsRepository;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 积分服务（独立限界上下文）。
 *
 * <p>它只订阅 OrderPaidEvent —— 订阅者只关心自己需要的最小事件，
 * 上游新增事件时不会波及下游。</p>
 */
@Service
public class LoyaltyPointsService {

    private final PointsRepository pointsRepository;

    public LoyaltyPointsService(PointsRepository pointsRepository) {
        this.pointsRepository = pointsRepository;
    }

    /** 发放积分，返回最新累计值。 */
    public int earn(String customerId, int pointsToEarn) {
        return pointsRepository.add(customerId, pointsToEarn);
    }

    public int pointsOf(String customerId) {
        return pointsRepository.pointsOf(customerId);
    }

    public Map<String, Integer> snapshot() {
        return pointsRepository.findAll();
    }
}
