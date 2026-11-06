package com.example.demo.txn.order.core;

import com.example.demo.txn.order.repository.OrderRepository;
import com.example.demo.txn.order.support.ParticipantClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 2PC 协调者：阶段一 Prepare B/C，阶段二按投票统一 Commit/Rollback。
 *
 * <p>决议规则（教科书版）：全员 YES → COMMIT；任何一个 NO 或不可达 → ROLLBACK。
 * 注意「不可达」也按 NO 处理——协调者无法确认参与者的状态时，
 * 唯一安全的选择是放弃（两阶段提交宁可一起失败也不允许部分成功）。</p>
 *
 * <p>故障实验：crashAfterPrepare=true 时，协调者在两个 Prepare 成功后「宕机」
 * ——不再发送二阶段，B/C 的未决事务（行锁、已扣减未提交的库存）无限期滞留，
 * 这就是 2PC 的两个著名缺陷的现场：参与者阻塞 + 协调者单点。
 * 恢复靠 POST /api/txn2pc/resolve 人工决议（对应真实 2PC 的日志恢复/人工介入）。</p>
 *
 * <p>坑：本协调器是单实例内存态，宕机实验的 pendingCrashes 重启即丢。
 * 真实协调者（如 Seata TC）会把事务状态持久化，宕机后可自动恢复决议。</p>
 */
@Service
public class TwoPcCoordinator {

    private final ParticipantClient client;
    private final OrderRepository orders;

    /** 宕机实验的未决现场：xid → 决议所需的参与者信息。resolve 依据它补发二阶段。 */
    private final Map<String, CrashContext> pendingCrashes = new ConcurrentHashMap<>();

    /** 一轮事务的逐步流水，作为响应返回给教程。 */
    private record CrashContext(String xid, int qty, int points) {
    }

    public TwoPcCoordinator(ParticipantClient client, OrderRepository orders) {
        this.client = client;
        this.orders = orders;
    }

    /**
     * 执行一次 2PC 下单。
     *
     * @param qty               数量（B 扣库存）
     * @param points            积分（C 扣积分）
     * @param crashAfterPrepare true = Prepare 后协调者宕机，不发送二阶段
     */
    public Map<String, Object> execute(int qty, int points, boolean crashAfterPrepare) {
        String xid = "2pc-" + UUID.randomUUID().toString().substring(0, 8);
        // 协调者自己的簿记也是本地事务：它记录「我发起过什么」，
        // 但注意——簿记无法阻止参与者阻塞，2PC 的正确性不靠它
        orders.insert(xid, "2PC", "C-1001", "P-1001", qty, points, "PREPARING", null);

        List<ParticipantClient.StepResult> steps = new ArrayList<>();
        // 阶段一：先 B 后 C。顺序固定，教程观察「Prepare B 成功、Prepare C 失败」的分支
        var prepB = client.post("inventory", "/api/txn2pc/prepare/" + xid,
                Map.of("productId", "P-1001", "qty", qty));
        steps.add(prepB);
        var prepC = prepB.ok()
                ? client.post("loyalty", "/api/txn2pc/prepare/" + xid, Map.of("points", points))
                : null;
        if (prepC != null) {
            steps.add(prepC);
        }

        boolean allYes = prepB.ok() && prepC != null && prepC.ok();

        if (crashAfterPrepare && allYes) {
            // 宕机现场：B/C 都投了 YES、都在等决议。不发送任何二阶段请求，
            // 库存行锁滞留、订单停在 IN_DOUBT——等待人工 resolve
            pendingCrashes.put(xid, new CrashContext(xid, qty, points));
            orders.updateStatus(xid, "IN_DOUBT", "协调者宕机：两个参与者已 Prepare，等待人工决议");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("xid", xid);
            out.put("scheme", "2PC");
            out.put("crashed", true);
            out.put("steps", steps);
            out.put("note", "协调者已在 Prepare 后宕机。B/C 的事务处于 in-doubt："
                    + "库存行被锁、扣减未提交。用 GET /api/txn2pc/pending 与 POST /api/txn2pc/resolve 恢复。");
            return out;
        }

        // 阶段二：决议。只有投 YES 的参与者需要收到二阶段（NO 的已自行回滚）
        String decision = allYes ? "COMMIT" : "ROLLBACK";
        List<ParticipantClient.StepResult> decisions = new ArrayList<>();
        if (prepB.ok()) {
            decisions.add(client.post("inventory", "/api/txn2pc/" + (allYes ? "commit/" : "rollback/") + xid, null));
        }
        if (prepC != null && prepC.ok()) {
            decisions.add(client.post("loyalty", "/api/txn2pc/" + (allYes ? "commit/" : "rollback/") + xid, null));
        }
        steps.addAll(decisions);

        orders.updateStatus(xid, allYes ? "CONFIRMED" : "CANCELLED",
                allYes ? "全员 YES，全局提交" : "存在 NO/不可达，全局回滚");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("xid", xid);
        out.put("scheme", "2PC");
        out.put("decision", decision);
        out.put("steps", steps);
        out.put("orderStatus", allYes ? "CONFIRMED" : "CANCELLED");
        return out;
    }

    /** 宕机协调者的未决清单：人工恢复前先看「有哪些事务在等决议」。 */
    public Map<String, Object> pending() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", pendingCrashes.size());
        out.put("crashedTransactions", pendingCrashes.keySet());
        return out;
    }

    /**
     * 人工决议：向当初 Prepare 成功的参与者补发 commit/rollback。
     * 现实中这一步由协调者恢复日志驱动；demo 里人来扮演恢复程序。
     */
    public Map<String, Object> resolve(String xid, String decision) {
        CrashContext ctx = pendingCrashes.remove(xid);
        if (ctx == null) {
            throw new IllegalArgumentException("xid=" + xid + " 不在宕机清单中（可能已决议）");
        }
        boolean commit = "commit".equalsIgnoreCase(decision);
        if (!commit && !"rollback".equalsIgnoreCase(decision)) {
            throw new IllegalArgumentException("decision 只能是 commit 或 rollback");
        }
        List<ParticipantClient.StepResult> steps = new ArrayList<>();
        steps.add(client.post("inventory", "/api/txn2pc/" + (commit ? "commit/" : "rollback/") + xid, null));
        steps.add(client.post("loyalty", "/api/txn2pc/" + (commit ? "commit/" : "rollback/") + xid, null));
        orders.updateStatus(xid, commit ? "CONFIRMED" : "CANCELLED", "人工决议：" + decision);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("xid", xid);
        out.put("decision", commit ? "COMMIT" : "ROLLBACK");
        out.put("steps", steps);
        return out;
    }
}
