package com.example.demo.txn.loyalty.controller;

import com.example.demo.txn.loyalty.core.TccParticipant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TCC 参与者接口（积分侧）。三大异常复现命令与 B 对齐：
 *
 * <pre>
 * # 空回滚 + 悬挂：先 Cancel 再 Try（同一 xid）
 * POST /api/tcc/cancel/manual-y1
 * POST /api/tcc/try/manual-y1?points=10
 *
 * # 幂等：同一 xid Cancel 两次
 * POST /api/tcc/cancel/manual-y0
 * POST /api/tcc/cancel/manual-y0
 * </pre>
 */
@RestController
@RequestMapping("/api/tcc")
public class TccController {

    private final TccParticipant participant;

    public TccController(TccParticipant participant) {
        this.participant = participant;
    }

    /** 一阶段：冻结积分。 */
    @PostMapping("/try/{xid}")
    public Map<String, Object> tryFreeze(@PathVariable String xid,
                                         @RequestParam(defaultValue = "C-1001") String customerId,
                                         @RequestParam(defaultValue = "10") int points) {
        return participant.tryFreeze(xid, customerId, points);
    }

    /** 二阶段确认：冻结额度永久扣除。 */
    @PostMapping("/confirm/{xid}")
    public Map<String, Object> confirm(@PathVariable String xid) {
        return participant.confirm(xid);
    }

    /** 二阶段取消：冻结额度解冻。 */
    @PostMapping("/cancel/{xid}")
    public Map<String, Object> cancel(@PathVariable String xid) {
        return participant.cancel(xid);
    }

    /** TCC 日志与防护开关现状。 */
    @GetMapping("/logs")
    public Map<String, Object> logs() {
        return participant.logs();
    }

    /** 运行时切换空回滚/悬挂防护。 */
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
