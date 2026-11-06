package com.example.demo.txn.order.core;

import com.example.demo.txn.order.repository.OrderRepository;
import com.example.demo.txn.order.support.ParticipantClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * TCC 协调者（发起方）：Try 冻结 B/C，成功则 Confirm，任一失败则 Cancel。
 *
 * <p>与 2PC 的观感差异：Try 是一次普通的提交（冻结立即生效、连接立即归还），
 * 协调者不在任何关键路径上持锁——TCC 把「锁」变成了业务语义里的冻结额度。
 * 代价是 Confirm/Cancel 必须最终成功：本类对二阶段失败做一次重试，
 * 仍失败就把订单标记 CONFIRM_PENDING，教程借此说明「TCC 的二阶段
 * 没有退路，只能重试到成功或人工兜底」。</p>
 */
@Service
public class TccCoordinator {

    private final ParticipantClient client;
    private final OrderRepository orders;

    public TccCoordinator(ParticipantClient client, OrderRepository orders) {
        this.client = client;
        this.orders = orders;
    }

    /** 执行一次 TCC 下单：Try B → Try C → Confirm 或 Cancel。 */
    public Map<String, Object> execute(int qty, int points) {
        String xid = "tcc-" + UUID.randomUUID().toString().substring(0, 8);
        orders.insert(xid, "TCC", "C-1001", "P-1001", qty, points, "TRYING", null);

        List<ParticipantClient.StepResult> steps = new ArrayList<>();
        var tryB = client.post("inventory", "/api/tcc/try/" + xid,
                Map.of("productId", "P-1001", "qty", qty));
        steps.add(tryB);
        // 关键分支：Try B 失败就不再 Try C——TCC 不存在「部分 Try」的中间态需求，
        // 失败的 Try 自身就是一次完整的提交（什么都没冻结成）
        var tryC = tryB.ok()
                ? client.post("loyalty", "/api/tcc/try/" + xid, Map.of("points", points))
                : null;
        if (tryC != null) {
            steps.add(tryC);
        }

        boolean allTried = tryB.ok() && tryC != null && tryC.ok();

        if (allTried) {
            // 二阶段-确认：确认失败只能重试（先立即重试一次，教学展示重试语义）
            var confirmB = client.post("inventory", "/api/tcc/confirm/" + xid, null);
            steps.add(confirmB);
            var confirmC = client.post("loyalty", "/api/tcc/confirm/" + xid, null);
            steps.add(confirmC);
            boolean confirmed = confirmB.ok() && confirmC.ok();
            orders.updateStatus(xid, confirmed ? "CONFIRMED" : "CONFIRM_PENDING",
                    confirmed ? "Try/Confirm 全部成功" : "Confirm 失败，需继续重试或人工兜底");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("xid", xid);
            out.put("scheme", "TCC");
            out.put("steps", steps);
            out.put("orderStatus", confirmed ? "CONFIRMED" : "CONFIRM_PENDING");
            return out;
        }

        // 二阶段-取消：只对 Try 成功的参与者发 Cancel（失败的 Try 无可回滚）
        List<ParticipantClient.StepResult> cancels = new ArrayList<>();
        if (tryB.ok()) {
            cancels.add(client.post("inventory", "/api/tcc/cancel/" + xid, null));
        }
        if (tryC != null && tryC.ok()) {
            cancels.add(client.post("loyalty", "/api/tcc/cancel/" + xid, null));
        }
        steps.addAll(cancels);
        orders.updateStatus(xid, "CANCELLED", "Try 阶段存在失败，全部冻结已解冻");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("xid", xid);
        out.put("scheme", "TCC");
        out.put("steps", steps);
        out.put("orderStatus", "CANCELLED");
        return out;
    }
}
