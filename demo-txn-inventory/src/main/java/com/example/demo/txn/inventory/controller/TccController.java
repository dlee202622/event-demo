package com.example.demo.txn.inventory.controller;

import com.example.demo.txn.inventory.core.TccParticipant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TCC 参与者接口。除正常流程外，三个「异常实验」也直接在这里手工触发：
 *
 * <pre>
 * # 空回滚 + 悬挂复现：先 Cancel（Try 永远不来）再 Try
 * POST /api/tcc/cancel/manual-x1
 * POST /api/tcc/try/manual-x1?qty=1
 *
 * # 幂等复现：同一个 xid Confirm 两次
 * POST /api/tcc/confirm/manual-x0
 * POST /api/tcc/confirm/manual-x0
 * </pre>
 *
 * <p>防护开关：POST /api/tcc/guard?enabled=false 关闭后重跑上面的命令，
 * 能看到未防护时的孤儿冻结。</p>
 */
@RestController
@RequestMapping("/api/tcc")
public class TccController {

    private final TccParticipant participant;

    public TccController(TccParticipant participant) {
        this.participant = participant;
    }

    /** 一阶段：冻结库存。result=FROZEN / TRIED_DUPLICATE / REJECTED_AFTER_CANCEL。 */
    @PostMapping("/try/{xid}")
    public Map<String, Object> tryFreeze(@PathVariable String xid,
                                         @RequestParam(defaultValue = "P-1001") String productId,
                                         @RequestParam(defaultValue = "1") int qty) {
        return participant.tryFreeze(xid, productId, qty);
    }

    /** 二阶段确认：冻结额度永久扣除。重复调用返回 CONFIRMED_DUPLICATE。 */
    @PostMapping("/confirm/{xid}")
    public Map<String, Object> confirm(@PathVariable String xid) {
        return participant.confirm(xid);
    }

    /** 二阶段取消：冻结额度解冻。Try 未到达时记录空回滚占位（防护开启时）。 */
    @PostMapping("/cancel/{xid}")
    public Map<String, Object> cancel(@PathVariable String xid) {
        return participant.cancel(xid);
    }

    /** TCC 日志：观察状态机 TRIED→CONFIRMED/CANCELLED/CANCELLED_EMPTY 的流转。 */
    @GetMapping("/logs")
    public Map<String, Object> logs() {
        return participant.logs();
    }

    /** 运行时切换空回滚/悬挂防护，教程做「防护前 vs 防护后」对比用。 */
    @PostMapping("/guard")
    public Map<String, Object> guard(@RequestParam boolean enabled) {
        participant.setGuard(enabled);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("guardEnabled", participant.guardEnabled());
        out.put("note", enabled ? "防护已开启：空回滚占位 + 悬挂拒绝 + 幂等生效"
                : "防护已关闭：迟到的 Try 会冻结成功且无人处理（悬挂）");
        return out;
    }
}
