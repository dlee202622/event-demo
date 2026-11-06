package com.example.demo.txn.loyalty.controller;

import com.example.demo.txn.loyalty.core.TwoPcParticipant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 2PC 参与者接口（积分侧）。手工复现路径与 B 对齐：
 *
 * <pre>
 * POST /api/txn2pc/prepare/{xid}?points=10   → 阶段一投票
 * POST /api/txn2pc/commit/{xid}              → 阶段二提交
 * POST /api/txn2pc/rollback/{xid}            → 阶段二回滚
 * GET  /api/txn2pc/status                    → 未决事务与日志
 * </pre>
 */
@RestController
@RequestMapping("/api/txn2pc")
public class Txn2pcController {

    private final TwoPcParticipant participant;

    public Txn2pcController(TwoPcParticipant participant) {
        this.participant = participant;
    }

    /** 阶段一投票：points 为本次要扣的积分（默认 10，即订单实验的标准价）。 */
    @PostMapping("/prepare/{xid}")
    public Map<String, Object> prepare(@PathVariable String xid,
                                       @RequestParam(defaultValue = "C-1001") String customerId,
                                       @RequestParam(defaultValue = "10") int points) {
        return participant.prepare(xid, customerId, points);
    }

    /** 阶段二提交。 */
    @PostMapping("/commit/{xid}")
    public Map<String, Object> commit(@PathVariable String xid) {
        return participant.commit(xid);
    }

    /** 阶段二回滚。 */
    @PostMapping("/rollback/{xid}")
    public Map<String, Object> rollback(@PathVariable String xid) {
        return participant.rollback(xid);
    }

    /** 未决事务与 Prepare 日志。 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return participant.status();
    }
}
