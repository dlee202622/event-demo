package com.example.demo.txn.loyalty.core;

import com.example.demo.txn.loyalty.repository.PointsRepository;
import com.example.demo.txn.loyalty.support.FaultRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Saga 参与者（积分侧）：正向操作直接扣积分并记流水，失败后由 A 调补偿接口加回。
 *
 * <p>与 TCC 的关键差异：没有冻结环节，扣减在本地事务里一提交就对外生效。
 * 好处是执行快、无中间状态管理；代价是【无隔离性】——在 A 还没来得及
 * 补偿的窗口里，任何读方都能看到「积分已被扣走」这个事实。</p>
 *
 * <p>比 B 的库存侧多一步 ledger 记账：扣减与补偿各记一条流水，
 * 教程实验后用 /api/points/{id}/ledger 能看到「一次 DEDUCT + 一次 COMPENSATE」
 * 的完整痕迹——Saga 的事务历史是「做了又撤」，不是「从未发生」。</p>
 *
 * <p>坑：补偿是「反向业务操作」不是回滚——如果正向扣减后、补偿前
 * 用户把积分花在了别处，补偿加回后总量虽对，过程已不等价。</p>
 */
@Service
public class SagaParticipant {

    public static final String BRANCH = "loyalty";

    private final JdbcTemplate jdbc;
    private final PointsRepository points;
    private final FaultRegistry fault;
    private final PlatformTransactionManager txManager;

    public SagaParticipant(JdbcTemplate jdbc, PointsRepository points,
                           FaultRegistry fault, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.points = points;
        this.fault = fault;
        this.txManager = txManager;
    }

    /**
     * 正向操作：直接扣减（带 msgId 幂等）。
     *
     * @param msgId 发送方生成的消息 ID，为 null 表示不带幂等（2PC/手动实验路径）
     * @return result = DEDUCTED(新扣) / DUPLICATE(重复消息，未再扣)
     */
    public Map<String, Object> deduct(String msgId, String customerId, int pointsToDeduct) {
        fault.check("loyalty saga deduct");
        TransactionStatus st = beginNew();
        try {
            if (msgId != null) {
                // INSERT IGNORE 撞主键 = 0 行：这条消息处理过，直接返回不再扣。
                // 幂等必须和扣减同事务：先登记再扣，崩溃在中间会让消息卡在「已登记未扣减」
                int inserted = jdbc.update(
                        "INSERT IGNORE INTO processed_msg (msg_id, detail) VALUES (?, ?)",
                        msgId, "loyalty deduct " + customerId + " -" + pointsToDeduct);
                if (inserted == 0) {
                    rollback(st);
                    return result(msgId, "DUPLICATE", "消息已处理过，未重复扣减");
                }
            }
            int balance = points.lockBalance(customerId);
            if (balance < pointsToDeduct) {
                throw new IllegalStateException("积分不足：balance=" + balance + " 需要=" + pointsToDeduct);
            }
            points.adjustBalance(customerId, -pointsToDeduct);
            // 流水与扣减同事务：账实一致，崩溃后流水也不会记多
            points.recordLedger(customerId, -pointsToDeduct, "DEDUCT", msgId);
            // 注意没有 prepare/tcc 之类的协议日志：Saga 的本地事务到这就结束了，
            // 「怎么撤销」不在参与者头上，而在协调者的补偿调用里
            txManager.commit(st);
            return result(msgId, "DEDUCTED", null);
        } catch (RuntimeException e) {
            rollback(st);
            throw e;
        }
    }

    /**
     * 补偿：把正向扣掉的积分加回，并记 COMPENSATE 流水。
     * 补偿与正向共用一个 msgId 体系但后缀不同（由 A 区分 -f/-c），
     * 防止「先扣后退」被同一个幂等键吞掉。
     */
    public Map<String, Object> compensate(String msgId, String customerId, int pointsToDeduct) {
        fault.check("loyalty saga compensate");
        TransactionStatus st = beginNew();
        try {
            int inserted = jdbc.update(
                    "INSERT IGNORE INTO processed_msg (msg_id, detail) VALUES (?, ?)",
                    msgId, "loyalty compensate " + customerId + " +" + pointsToDeduct);
            if (inserted == 0) {
                // 补偿重复投递：不虚增余额（A 重试补偿时这里是最后一道闸）
                rollback(st);
                return result(msgId, "DUPLICATE", "补偿已执行过，未重复加回");
            }
            points.adjustBalance(customerId, pointsToDeduct);
            points.recordLedger(customerId, pointsToDeduct, "COMPENSATE", msgId);
            txManager.commit(st);
            return result(msgId, "COMPENSATED", null);
        } catch (RuntimeException e) {
            rollback(st);
            throw e;
        }
    }

    /** 独立新事务 + 异常路径显式回滚：手动事务的固定开场与收尾。 */
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

    private Map<String, Object> result(String msgId, String phaseResult, String note) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branch", BRANCH);
        if (msgId != null) {
            out.put("msgId", msgId);
        }
        out.put("result", phaseResult);
        if (note != null) {
            out.put("note", note);
        }
        return out;
    }
}
