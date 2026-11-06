package com.example.demo.txn.order.controller;

import com.example.demo.txn.order.core.TwoPcCoordinator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 2PC 协调者入口。三条实验路径：
 *
 * <pre>
 * POST /api/txn2pc/order                          # 正常：Prepare B/C → Commit
 * POST /api/txn2pc/order?crashAfterPrepare=true   # 宕机：Prepare 后停住，B/C in-doubt
 * POST /api/txn2pc/resolve?xid=...&amp;decision=commit  # 人工决议（宕机恢复）
 * </pre>
 *
 * <p>最长阻塞：无（宕机路径立即返回）；fault=HANG 时跟随参与者的 hangMs。</p>
 */
@RestController
@RequestMapping("/api/txn2pc")
public class TwoPcController {

    private final TwoPcCoordinator coordinator;

    public TwoPcController(TwoPcCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    /** 发起 2PC 下单。响应里的 steps 数组是逐跳协议流水（Prepare → 决议）。 */
    @PostMapping("/order")
    public Map<String, Object> order(@RequestParam(defaultValue = "1") int qty,
                                     @RequestParam(defaultValue = "10") int points,
                                     @RequestParam(defaultValue = "false") boolean crashAfterPrepare) {
        return coordinator.execute(qty, points, crashAfterPrepare);
    }

    /** 宕机协调者的未决事务清单。 */
    @GetMapping("/pending")
    public Map<String, Object> pending() {
        return coordinator.pending();
    }

    /** 人工决议（commit / rollback）：模拟协调者恢复后的补发二阶段。 */
    @PostMapping("/resolve")
    public Map<String, Object> resolve(@RequestParam String xid, @RequestParam String decision) {
        return coordinator.resolve(xid, decision);
    }
}
