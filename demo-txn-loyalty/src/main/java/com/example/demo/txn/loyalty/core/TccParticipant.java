package com.example.demo.txn.loyalty.core;

import com.example.demo.txn.loyalty.repository.PointsRepository;
import com.example.demo.txn.loyalty.support.FaultRegistry;
import org.springframework.core.env.Environment;
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
 * TCC 参与者（积分侧）：Try 冻结、Confirm 扣冻结额度、Cancel 解冻。
 *
 * <p>与 2PC 的本质区别：Try 提交的是一个「业务上可见的中间状态」
 * （balance→frozen 转移），不持有跨请求的数据库事务和行锁——
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
 * <p>与 B 的实现同一套状态机，但 tcc_log 里额外保存 customer_id——
 * 二阶段不需要任何业务参数就能还原到正确账户，这是 TCC 日志的标准写法：
 * Try 时把「未来 Cancel 需要的一切」都记下来。</p>
 */
@Service
public class TccParticipant {

    public static final String BRANCH = "loyalty";

    private final JdbcTemplate jdbc;
    private final PointsRepository points;
    private final FaultRegistry fault;
    private final PlatformTransactionManager txManager;

    /**
     * 空回滚/悬挂防护开关。默认取配置项（true），可运行时用
     * POST /api/tcc/guard 切换，方便教程里做「防护前 vs 防护后」的对比实验。
     */
    private final AtomicBoolean guardEnabled;

    public TccParticipant(JdbcTemplate jdbc, PointsRepository points, FaultRegistry fault,
                          PlatformTransactionManager txManager, Environment env) {
        this.jdbc = jdbc;
        this.points = points;
        this.fault = fault;
        this.txManager = txManager;
        this.guardEnabled = new AtomicBoolean(
                env.getProperty("demo.txn.loyalty.tcc-guard-enabled", Boolean.class, true));
    }

    /** 供 HTTP 层回显当前防护状态。 */
    public boolean guardEnabled() {
        return guardEnabled.get();
    }

    /** 运行时切换防护；实验收尾记得恢复 true，否则后续实验会带上悬挂副作用。 */
    public void setGuard(boolean enabled) {
        guardEnabled.set(enabled);
    }

    /**
     * 一阶段：冻结 pointsToDeduct 个积分（balance 转入 frozen）。
     *
     * @return result = FROZEN / TRIED_DUPLICATE(幂等命中) / REJECTED_AFTER_CANCEL(悬挂防护)
     */
    public Map<String, Object> tryFreeze(String xid, String customerId, int pointsToDeduct) {
        fault.check("loyalty TCC try");
        TransactionStatus st = beginNew();
        try {
            // 先锁 tcc_log 行再动资源：同一 xid 的并发 Try/Cancel/Confirm 在这里串行化。
            // 这是三大异常防护的「咽喉」——所有分支都先抢这把行锁再说话
            Map<String, Object> log = lockTccLog(xid);
            if (log != null) {
                String state = (String) log.get("state");
                if (state.startsWith("CANCELLED")) {
                    // 悬挂防护：日志里已是 CANCELLED，说明 Cancel 先到了。
                    // 此时若照常冻结，没有任何人会来 Confirm/Cancel 这笔冻结额度——孤儿资源
                    rollback(st);
                    return result(xid, "REJECTED_AFTER_CANCEL", "Cancel 已先行处理该事务，Try 作废（悬挂防护）",
                            customerId, pointsToDeduct);
                }
                if ("TRIED".equals(state)) {
                    rollback(st);
                    // 幂等：Try 重试不重复冻结，直接返回上次的成功结果
                    return result(xid, "TRIED_DUPLICATE", "该事务已冻结过，本次为重复请求",
                            customerId, pointsToDeduct);
                }
                // CANCELLED/CONFIRMED 之外的状态不该出现在 Try 阶段，防御性拒绝
                rollback(st);
                return result(xid, "REJECTED_INVALID_STATE", "日志状态 " + state + " 不允许再次 Try",
                        customerId, pointsToDeduct);
            }
            // 防护关闭时直接落到下面冻结——这正是「孤儿冻结」的成因
            int balance = points.lockBalance(customerId);
            if (balance < pointsToDeduct) {
                throw new IllegalStateException("积分不足：balance=" + balance + " 需要=" + pointsToDeduct);
            }
            // 冻结 = 两个 UPDATE 同事务：对读方而言 balance 变少、frozen 变多，
            // 「资源被我占用但没消费」这个事实立即对外可见（对比 2PC 的完全不可见）
            points.adjustBalance(customerId, -pointsToDeduct);
            points.adjustFrozen(customerId, pointsToDeduct);
            // 把 customerId 记进日志：Cancel 只带 xid 也能找到还原对象
            jdbc.update(
                    "INSERT INTO tcc_log (xid, branch, state, customer_id, points) VALUES (?, ?, 'TRIED', ?, ?)",
                    xid, BRANCH, customerId, pointsToDeduct);
            txManager.commit(st);
            return result(xid, "FROZEN", null, customerId, pointsToDeduct);
        } catch (RuntimeException e) {
            rollback(st);
            throw e; // 积分不足等业务失败上抛给 HTTP 层映射 409，A 据此决定 Cancel
        }
    }

    /**
     * 二阶段-确认：把冻结额度永久扣掉（frozen -= pointsToDeduct）。
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
            int pts = ((Number) log.get("points")).intValue();
            // customer_id 从日志取而不是参数传：二阶段调用方可能只带 xid
            String customerId = (String) log.get("customer_id");
            if ("CONFIRMED".equals(state)) {
                rollback(st);
                // 幂等命中：网络重试导致的重复 Confirm 在这里被吸收
                return result(xid, "CONFIRMED_DUPLICATE", "已确认过，本次为重复请求", customerId, pts);
            }
            if (!"TRIED".equals(state)) {
                rollback(st);
                throw new IllegalStateException("日志状态 " + state + " 不允许 Confirm");
            }
            points.adjustFrozen(customerId, -pts);
            jdbc.update("UPDATE tcc_log SET state = 'CONFIRMED' WHERE xid = ? AND branch = ?", xid, BRANCH);
            txManager.commit(st);
            return result(xid, "CONFIRMED", null, customerId, pts);
        } catch (RuntimeException e) {
            rollback(st);
            throw e;
        }
    }

    /**
     * 二阶段-取消：把冻结额度还回 balance。同时承担空回滚防护：
     * 没有 Try 记录时插入 CANCELLED_EMPTY 占位，让「迟到的 Try」
     * 能从日志里看见 Cancel 已经来过。
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
                    return result(xid, "EMPTY_ROLLBACK_UNPROTECTED", "无 Try 记录，未做占位（防护关闭，悬挂风险）",
                            null, 0);
                }
                // 空回滚：Try 尚未到达。占位一条 CANCELLED_EMPTY，
                // 迟到的 Try 会读到它并拒绝执行（悬挂防护生效）
                jdbc.update(
                        "INSERT INTO tcc_log (xid, branch, state, customer_id, points) VALUES (?, ?, 'CANCELLED_EMPTY', '(unknown)', 0)",
                        xid, BRANCH);
                txManager.commit(st);
                return result(xid, "EMPTY_ROLLBACK", "Try 未到达，已记录空回滚占位（空回滚防护）", null, 0);
            }
            String state = (String) log.get("state");
            int pts = ((Number) log.get("points")).intValue();
            String customerId = (String) log.get("customer_id");
            if (state.startsWith("CANCELLED")) {
                rollback(st);
                return result(xid, "CANCELLED_DUPLICATE", "已取消过，本次为重复请求", customerId, pts);
            }
            if (!"TRIED".equals(state)) {
                rollback(st);
                throw new IllegalStateException("日志状态 " + state + " 不允许 Cancel");
            }
            // 解冻：balance 还回、frozen 释放，两个 UPDATE 同事务
            points.adjustBalance(customerId, pts);
            points.adjustFrozen(customerId, -pts);
            jdbc.update("UPDATE tcc_log SET state = 'CANCELLED' WHERE xid = ? AND branch = ?", xid, BRANCH);
            txManager.commit(st);
            return result(xid, "CANCELLED", null, customerId, pts);
        } catch (RuntimeException e) {
            rollback(st);
            throw e;
        }
    }

    /** 日志全量：教程观察状态机 TRIED→CONFIRMED/CANCELLED/CANCELLED_EMPTY 的流转。 */
    public Map<String, Object> logs() {
        return Map.of(
                "branch", BRANCH,
                "guardEnabled", guardEnabled.get(),
                "logs", jdbc.queryForList(
                        "SELECT xid, state, customer_id, points, created_at, updated_at FROM tcc_log ORDER BY created_at"));
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

    /** FOR UPDATE 读日志行；不存在返回 null（FOR UPDATE 对不存在的行是间隙锁，演示规模下够用）。 */
    private Map<String, Object> lockTccLog(String xid) {
        var rows = jdbc.queryForList(
                "SELECT state, customer_id, points FROM tcc_log WHERE xid = ? AND branch = ? FOR UPDATE",
                xid, BRANCH);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> result(String xid, String phaseResult, String note, String customerId, int pts) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branch", BRANCH);
        out.put("xid", xid);
        out.put("result", phaseResult);
        if (customerId != null) {
            out.put("customerId", customerId);
        }
        out.put("points", pts);
        if (note != null) {
            out.put("note", note);
        }
        return out;
    }
}
