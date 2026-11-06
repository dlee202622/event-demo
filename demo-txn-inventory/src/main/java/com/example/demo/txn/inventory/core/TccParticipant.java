package com.example.demo.txn.inventory.core;

import com.example.demo.txn.inventory.repository.StockRepository;
import com.example.demo.txn.inventory.support.FaultRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCC 参与者（库存侧）：Try 冻结、Confirm 扣减冻结额度、Cancel 解冻。
 *
 * <p>与 2PC 的本质区别：Try 提交的是一个「业务上可见的中间状态」
 * （available→frozen 转移），不持有跨请求的数据库事务和行锁——
 * 所以 TCC 没有连接滞留问题，代价是必须自己处理三个经典异常：</p>
 * <ul>
 *   <li>空回滚——Cancel 比 Try 先到（Try 请求丢了/超时了），无东西可回滚；</li>
 *   <li>悬挂——Cancel 处理完后迟到的 Try 才执行，冻结变成没人管的孤儿；</li>
 *   <li>重复决议——Confirm/Cancel 因网络重试被执行两次，额度被扣两遍。</li>
 * </ul>
 *
 * <p>三个异常的统一解法：tcc_log 表 + 「先查日志再动资源、查和改在同一事务」。
 * {@code guardEnabled=false} 时防护关闭，教程先复现异常再打开对比。</p>
 *
 * <p>坑：查日志必须 FOR UPDATE 且与业务写在同一本地事务，否则
 * check-then-act 之间并发进来第二个 Try/Cancel，防护形同虚设。</p>
 */
@Service
public class TccParticipant {

    public static final String BRANCH = "inventory";

    private final JdbcTemplate jdbc;
    private final StockRepository stock;
    private final FaultRegistry fault;
    private final PlatformTransactionManager txManager;

    /**
     * 空回滚/悬挂防护开关。默认取配置项（true），可运行时用
     * POST /api/tcc/guard 切换，方便教程里做「防护前 vs 防护后」的对比实验。
     */
    private final AtomicBoolean guardEnabled;

    public TccParticipant(JdbcTemplate jdbc, StockRepository stock, FaultRegistry fault,
                          PlatformTransactionManager txManager, org.springframework.core.env.Environment env) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.fault = fault;
        this.txManager = txManager;
        this.guardEnabled = new AtomicBoolean(
                env.getProperty("demo.txn.inventory.tcc-guard-enabled", Boolean.class, true));
    }

    public boolean guardEnabled() {
        return guardEnabled.get();
    }

    public void setGuard(boolean enabled) {
        guardEnabled.set(enabled);
    }

    /**
     * 一阶段：冻结 qty 个库存（available 转入 frozen）。
     *
     * @return phaseResult = FROZEN / TRIED_DUPLICATE(幂等命中) / REJECTED_AFTER_CANCEL(悬挂防护)
     */
    public Map<String, Object> tryFreeze(String xid, String productId, int qty) {
        fault.check("inventory TCC try");
        TransactionStatus st = beginNew();
        try {
            // 锁 tcc_log 行：同一 xid 的并发 Try/Cancel/Confirm 在这里串行化。
            // 这是三大异常防护的「咽喉」——所有分支都先抢这把行锁再说话。
            Map<String, Object> log = lockTccLog(xid);
            if (log != null) {
                String state = (String) log.get("state");
                // 悬挂防护：日志里已是 CANCELLED，说明 Cancel 先到了。
                // 此时若照常冻结，没有任何人会来 Confirm/Cancel 这笔冻结额度——孤儿资源。
                if (state.startsWith("CANCELLED")) {
                    rollback(st);
                    return result(xid, "REJECTED_AFTER_CANCEL", "Cancel 已先行处理该事务，Try 作废（悬挂防护）");
                }
                if ("TRIED".equals(state)) {
                    rollback(st);
                    // 幂等：Try 重试不重复冻结，直接返回上次的成功结果
                    return result(xid, "TRIED_DUPLICATE", "该事务已冻结过，本次为重复请求");
                }
                // TRIED → 其他状态不可能出现在 Try 阶段，防御性拒绝
                rollback(st);
                return result(xid, "REJECTED_INVALID_STATE", "日志状态 " + state + " 不允许再次 Try");
            }
            // 防护关闭时直接跳到下面冻结——这正是「孤儿冻结」的成因
            int available = stock.lockAvailable(productId);
            if (available < qty) {
                throw new IllegalStateException("库存不足：available=" + available + " 需要=" + qty);
            }
            // 冻结 = 两个 UPDATE 同事务：对读方而言 available 变少、frozen 变多，
            // 「资源被我占用但没消费」这个事实立即对外可见（对比 2PC 的完全不可见）
            stock.adjustAvailable(productId, -qty);
            stock.adjustFrozen(productId, qty);
            jdbc.update(
                    "INSERT INTO tcc_log (xid, branch, state, qty) VALUES (?, ?, 'TRIED', ?)",
                    xid, BRANCH, qty);
            txManager.commit(st);
            return result(xid, "FROZEN", null);
        } catch (RuntimeException e) {
            rollback(st);
            throw e; // 库存不足等业务失败上抛给 HTTP 层映射 409，A 据此决定 Cancel
        }
    }

    /**
     * 二阶段-确认：把冻结额度永久扣掉（frozen -= qty）。
     * Confirm 只会被「Try 全部成功」触发，理论必然成功；若真失败只能重试，
     * 没有别的出路——这就是 TCC 要求 Confirm/Cancel 幂等的原因。
     */
    public Map<String, Object> confirm(String xid) {
        TransactionStatus st = beginNew();
        try {
            Map<String, Object> log = lockTccLog(xid);
            if (log == null) {
                // 无 Try 记录却收到 Confirm：协议异常（或日志被清）。不能静默放行，
                // 否则 frozen 会被扣成负数
                rollback(st);
                throw new IllegalStateException("xid=" + xid + " 没有 Try 记录，拒绝 Confirm");
            }
            String state = (String) log.get("state");
            int qty = ((Number) log.get("qty")).intValue();
            if ("CONFIRMED".equals(state)) {
                rollback(st);
                // 幂等命中：网络重试导致的重复 Confirm 在这里被吸收
                return result(xid, "CONFIRMED_DUPLICATE", "已确认过，本次为重复请求");
            }
            if (!"TRIED".equals(state)) {
                rollback(st);
                throw new IllegalStateException("日志状态 " + state + " 不允许 Confirm");
            }
            stock.adjustFrozen(productIdOf(xid), -qty);
            jdbc.update("UPDATE tcc_log SET state = 'CONFIRMED' WHERE xid = ? AND branch = ?", xid, BRANCH);
            txManager.commit(st);
            return result(xid, "CONFIRMED", null);
        } catch (RuntimeException e) {
            rollback(st);
            throw e;
        }
    }

    /**
     * 二阶段-取消：把冻结额度还回 available（available += qty, frozen -= qty）。
     * 同时承担空回滚防护：没有 Try 记录时插入 CANCELLED_EMPTY 占位，
     * 让「迟到的 Try」能从日志里看见 Cancel 已经来过。
     */
    public Map<String, Object> cancel(String xid) {
        TransactionStatus st = beginNew();
        try {
            Map<String, Object> log = lockTccLog(xid);
            if (log == null) {
                if (!guardEnabled.get()) {
                    // 防护关闭：返回 OK 但什么都没记。之后迟到的 Try 照样冻结成功，
                    // 而这笔冻结永远没人 Confirm/Cancel——悬挂成立，教程用这个复现
                    rollback(st);
                    return result(xid, "EMPTY_ROLLBACK_UNPROTECTED", "无 Try 记录，未做占位（防护关闭，悬挂风险）");
                }
                // 空回滚：Try 尚未到达。占位一条 CANCELLED_EMPTY，
                // 迟到的 Try 会读到它并拒绝执行（悬挂防护生效）
                jdbc.update(
                        "INSERT INTO tcc_log (xid, branch, state, qty) VALUES (?, ?, 'CANCELLED_EMPTY', 0)",
                        xid, BRANCH);
                txManager.commit(st);
                return result(xid, "EMPTY_ROLLBACK", "Try 未到达，已记录空回滚占位（空回滚防护）");
            }
            String state = (String) log.get("state");
            int qty = ((Number) log.get("qty")).intValue();
            if (state.startsWith("CANCELLED")) {
                rollback(st);
                return result(xid, "CANCELLED_DUPLICATE", "已取消过，本次为重复请求");
            }
            if (!"TRIED".equals(state)) {
                rollback(st);
                throw new IllegalStateException("日志状态 " + state + " 不允许 Cancel");
            }
            stock.adjustAvailable(productIdOf(xid), qty);
            stock.adjustFrozen(productIdOf(xid), -qty);
            jdbc.update("UPDATE tcc_log SET state = 'CANCELLED' WHERE xid = ? AND branch = ?", xid, BRANCH);
            txManager.commit(st);
            return result(xid, "CANCELLED", null);
        } catch (RuntimeException e) {
            rollback(st);
            throw e;
        }
    }

    /** 日志全量：教程观察状态机流转用。 */
    public Map<String, Object> logs() {
        return Map.of(
                "branch", BRANCH,
                "guardEnabled", guardEnabled.get(),
                "logs", jdbc.queryForList(
                        "SELECT xid, state, qty, created_at, updated_at FROM tcc_log ORDER BY created_at"));
    }

    /** 每个协议方法都从它开始：独立新事务 + 异常路径必须显式回滚，Spring 不会替手动事务兜底。 */
    private TransactionStatus beginNew() {
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return txManager.getTransaction(def);
    }

    private void rollback(TransactionStatus st) {
        if (!st.isCompleted()) {
            txManager.rollback(st);
        }
    }

    /** FOR UPDATE 读日志行；不存在返回 null（注意：FOR UPDATE 对不存在的行是间隙锁，演示规模下够用）。 */
    private Map<String, Object> lockTccLog(String xid) {
        var rows = jdbc.queryForList(
                "SELECT state, qty FROM tcc_log WHERE xid = ? AND branch = ? FOR UPDATE", xid, BRANCH);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * tcc_log 没存 productId：教学简化——本演示固定只有 P-1001 一种商品。
     * 生产实现必须把 productId 冻结进日志，否则 Cancel 阶段不知道还给谁。
     */
    private String productIdOf(String xid) {
        // 固定商品 ID：与 data.sql 的种子数据一致
        return "P-1001";
    }

    private Map<String, Object> result(String xid, String phaseResult, String note) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branch", BRANCH);
        out.put("xid", xid);
        out.put("result", phaseResult);
        if (note != null) {
            out.put("note", note);
        }
        return out;
    }
}
