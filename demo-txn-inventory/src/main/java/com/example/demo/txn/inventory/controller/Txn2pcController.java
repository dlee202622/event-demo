package com.example.demo.txn.inventory.controller;

import com.example.demo.txn.inventory.core.TwoPcParticipant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 2PC 参与者接口。调用方是协调者 A（demo-txn-order），教程里也可以手工 curl：
 *
 * <pre>
 * POST /api/txn2pc/prepare?xid=2pc-manual-1        → 阶段一投票（事务保持打开）
 * POST /api/txn2pc/commit?xid=2pc-manual-1         → 阶段二提交（锁释放）
 * GET  /api/txn2pc/status                          → 未决事务与日志（宕机恢复观测点）
 * </pre>
 *
 * <p>最长阻塞时间：fault=HANG 时 prepare 阻塞 hangMs（默认 8s），其余立即返回。</p>
 */
@RestController
@RequestMapping("/api/txn2pc")
public class Txn2pcController {

    private final TwoPcParticipant participant;

    public Txn2pcController(TwoPcParticipant participant) {
        this.participant = participant;
    }

    /** 阶段一投票：成功 = 事务已扣减但未提交（YES）；库存不足 = 回滚（NO）。 */
    @PostMapping("/prepare/{xid}")
    public Map<String, Object> prepare(@PathVariable String xid,
                                       @RequestParam(defaultValue = "P-1001") String productId,
                                       @RequestParam(defaultValue = "1") int qty) {
        return participant.prepare(xid, productId, qty);
    }

    /** 阶段二提交：仅当协调者收到全员 YES 才会到来。 */
    @PostMapping("/commit/{xid}")
    public Map<String, Object> commit(@PathVariable String xid) {
        return participant.commit(xid);
    }

    /** 阶段二回滚：把 Prepare 扣掉的库存加回去。 */
    @PostMapping("/rollback/{xid}")
    public Map<String, Object> rollback(@PathVariable String xid) {
        return participant.rollback(xid);
    }

    /** 未决事务与 Prepare 日志：协调者宕机后，这里是参与者唯一的「我在等谁」信息。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return participant.status();
    }
}
