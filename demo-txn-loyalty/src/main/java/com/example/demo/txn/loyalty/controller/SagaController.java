package com.example.demo.txn.loyalty.controller;

import com.example.demo.txn.loyalty.core.SagaParticipant;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Saga 参与者接口（积分侧）：正向 deduct / 补偿 compensate。
 * 扣减与补偿都记 points_ledger 流水，实验后可用 /api/points/{id}/ledger 核对痕迹。
 */
@RestController
@RequestMapping("/api/saga")
public class SagaController {

    private final SagaParticipant participant;

    public SagaController(SagaParticipant participant) {
        this.participant = participant;
    }

    /** 正向：直接扣积分（立即对外可见，Saga 无隔离性）。result=DEDUCTED / DUPLICATE。 */
    @PostMapping("/deduct/{customerId}")
    public Map<String, Object> deduct(@PathVariable String customerId,
                                      @RequestParam(defaultValue = "10") int points,
                                      @RequestParam(required = false) String msgId) {
        return participant.deduct(msgId, customerId, points);
    }

    /** 补偿：加回积分并记 COMPENSATE 流水。 */
    @PostMapping("/compensate/{customerId}")
    public Map<String, Object> compensate(@PathVariable String customerId,
                                          @RequestParam(defaultValue = "10") int points,
                                          @RequestParam(required = false) String msgId) {
        return participant.compensate(msgId, customerId, points);
    }
}
