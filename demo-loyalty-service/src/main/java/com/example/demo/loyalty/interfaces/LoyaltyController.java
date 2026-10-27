package com.example.demo.loyalty.interfaces;

import com.example.demo.loyalty.LoyaltyPointsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 积分查询接口（只读，便于观察事件驱动的结果）。
 */
@RestController
@RequestMapping("/api/loyalty")
public class LoyaltyController {

    private final LoyaltyPointsService loyaltyPointsService;

    public LoyaltyController(LoyaltyPointsService loyaltyPointsService) {
        this.loyaltyPointsService = loyaltyPointsService;
    }

    @GetMapping("/points/{customerId}")
    public Map<String, Object> pointsOf(@PathVariable String customerId) {
        return Map.of("customerId", customerId, "points", loyaltyPointsService.pointsOf(customerId));
    }

    @GetMapping("/points")
    public Map<String, Integer> snapshot() {
        return loyaltyPointsService.snapshot();
    }
}
