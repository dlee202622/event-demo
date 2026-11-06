package com.example.demo.txn.inventory.controller;

import com.example.demo.txn.inventory.core.SagaParticipant;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Saga 参与者接口：正向 deduct / 补偿 compensate，一一对应。
 *
 * <p>msgId 幂等是给「本地消息表」方案的发送方重试用的；
 * Saga 编排路径（A 直接调用）也传 msgId，用同一个去重表，顺带演示
 * 「补偿消息重复投递不会虚增库存」。</p>
 */
@RestController
@RequestMapping("/api/saga")
public class SagaController {

    private final SagaParticipant participant;

    public SagaController(SagaParticipant participant) {
        this.participant = participant;
    }

    /** 正向：直接扣库存并立即对外可见（无隔离性窗口）。result=DEDUCTED / DUPLICATE。 */
    @PostMapping("/deduct/{productId}")
    public Map<String, Object> deduct(@PathVariable String productId,
                                      @RequestParam(defaultValue = "1") int qty,
                                      @RequestParam(required = false) String msgId) {
        return participant.deduct(msgId, productId, qty);
    }

    /** 补偿：把正向扣掉的加回。同 msgId 重复补偿返回 DUPLICATE。 */
    @PostMapping("/compensate/{productId}")
    public Map<String, Object> compensate(@PathVariable String productId,
                                          @RequestParam(defaultValue = "1") int qty,
                                          @RequestParam(required = false) String msgId) {
        return participant.compensate(msgId, productId, qty);
    }
}
