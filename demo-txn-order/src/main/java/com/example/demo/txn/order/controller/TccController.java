package com.example.demo.txn.order.controller;

import com.example.demo.txn.order.core.TccCoordinator;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * TCC 协调者入口：POST /api/tcc/order。
 * Try 阶段的失败路径靠 B/C 的故障注入（POST /api/fault?mode=FAIL）触发；
 * 三大异常（空回滚/悬挂/幂等）直接 curl B/C 的 /api/tcc/* 手工复现，见模块 README。
 */
@RestController
@RequestMapping("/api/tcc")
public class TccController {

    private final TccCoordinator coordinator;

    public TccController(TccCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    /** 发起 TCC 下单：Try B/C → Confirm 或 Cancel，steps 数组逐跳展示。 */
    @PostMapping("/order")
    public Map<String, Object> order(@RequestParam(defaultValue = "1") int qty,
                                     @RequestParam(defaultValue = "10") int points) {
        return coordinator.execute(qty, points);
    }
}
