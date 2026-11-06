package com.example.demo.txn.order.controller;

import com.example.demo.txn.order.core.SagaCoordinator;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Saga 协调者入口。隔离性实验：
 *
 * <pre>
 * # 终端 1：把 C 打成 FAIL，发起 Saga，compensateDelayMs 拉宽补偿前的窗口
 * curl -X POST 'http://localhost:9010/api/fault?mode=FAIL'
 * curl -X POST 'http://localhost:9008/api/saga/order?compensateDelayMs=5000'
 * # 窗口 5 秒内在终端 2 查询 B：库存已经少 1（无隔离性，与 TCC 的冻结对比）
 * curl http://localhost:9009/api/stock/P-1001
 * </pre>
 */
@RestController
@RequestMapping("/api/saga")
public class SagaController {

    private final SagaCoordinator coordinator;

    public SagaController(SagaCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    /** 发起 Saga 下单。compensateDelayMs 上限 15000，防止 HTTP 读超时把实验搞砸。 */
    @PostMapping("/order")
    public Map<String, Object> order(@RequestParam(defaultValue = "1") int qty,
                                     @RequestParam(defaultValue = "10") int points,
                                     @RequestParam(defaultValue = "0") long compensateDelayMs) {
        long delay = Math.min(Math.max(compensateDelayMs, 0), 15_000);
        return coordinator.execute(qty, points, delay);
    }
}
