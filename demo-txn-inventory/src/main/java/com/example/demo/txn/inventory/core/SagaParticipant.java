package com.example.demo.txn.inventory.core;

import com.example.demo.txn.inventory.support.FaultRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Saga 参与者（库存侧）：正向操作直接扣库存，失败后由 A 调补偿接口加回。
 *
 * <p>与 TCC 的关键差异：没有冻结环节，扣减在本地事务里一提交就对外生效。
 * 好处是执行快、无中间状态管理；代价是【无隔离性】——在 C 扣积分失败、
 * A 还没来得及补偿的窗口里，任何读方都能看到「库存已被扣走」这个事实，
 * 教程里用 compensateDelayMs 参数把这个窗口拉宽做观测。</p>
 *
 * <p>同时承担本地消息表方案的执行端：带 msgId 的调用用 processed_msg
 * 表去重（发送方会重试，同一消息可能到达多次，业务必须只扣一次）。</p>
 *
 * <p>坑：补偿是「反向业务操作」不是回滚——加回的库存和扣掉的库存在
 * 两个独立事务里，中间若有第三方拿到了扣减期间的销售机会，补偿后
 * 总量不变但过程不等价。这就是 Saga 定义里的「语义锁定」问题。</p>
 */
@Service
public class SagaParticipant {

    public static final String BRANCH = "inventory";

    private final JdbcTemplate jdbc;
    private final com.example.demo.txn.inventory.repository.StockRepository stock;
    private final FaultRegistry fault;
    private final PlatformTransactionManager txManager;

    public SagaParticipant(JdbcTemplate jdbc, com.example.demo.txn.inventory.repository.StockRepository stock,
                           FaultRegistry fault, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.stock = stock;
        this.fault = fault;
        this.txManager = txManager;
    }

    /**
     * 正向操作：直接扣减（带 msgId 幂等）。
     *
     * @param msgId 发送方生成的消息 ID，为 null 表示不带幂等（2PC/手动实验路径）
     * @return result = DEDUCTED(新扣) / DUPLICATE(重复消息，未再扣)
     */
    public Map<String, Object> deduct(String msgId, String productId, int qty) {
        fault.check("inventory saga deduct");
        TransactionStatus st = beginNew();
        try {
            if (msgId != null) {
                int inserted = jdbc.update(
                        "INSERT IGNORE INTO processed_msg (msg_id, detail) VALUES (?, ?)",
                        msgId, "inventory deduct " + productId + " x" + qty);
                if (inserted == 0) {
                    // INSERT IGNORE 撞主键 = 0 行：这条消息处理过，直接返回，不再扣库存。
                    // 幂等必须和扣减同事务：先登记再扣，崩溃在中间会让消息永远卡在「已处理未扣减」
                    rollback(st);
                    return result(msgId, "DUPLICATE", "消息已处理过，未重复扣减");
                }
            }
            int available = stock.lockAvailable(productId);
            if (available < qty) {
                throw new IllegalStateException("库存不足：available=" + available + " 需要=" + qty);
            }
            stock.adjustAvailable(productId, -qty);
            // 注意没有 prepare/tcc 之类的协议日志：Saga 的本地事务到这就结束了，
            // 「怎么撤销」这件事不在参与者头上，而在协调者的补偿调用里
            txManager.commit(st);
            return result(msgId, "DEDUCTED", null);
        } catch (RuntimeException e) {
            rollback(st);
            throw e;
        }
    }

    /**
     * 补偿：把正向扣掉的库存加回。参数与 deduct 对称，补偿也带同一个 msgId
     * 幂等（防止 A 重试补偿导致库存虚增）。
     */
    public Map<String, Object> compensate(String msgId, String productId, int qty) {
        fault.check("inventory saga compensate");
        TransactionStatus st = beginNew();
        try {
            int inserted = jdbc.update(
                    "INSERT IGNORE INTO processed_msg (msg_id, detail) VALUES (?, ?)",
                    msgId, "inventory compensate " + productId + " x" + qty);
            if (inserted == 0) {
                rollback(st);
                return result(msgId, "DUPLICATE", "补偿已执行过，未重复加回");
            }
            // 补偿前不校验「是否真的扣过」：processed_msg 的登记就是扣过的凭证，
            // 两边共用同一 msgId 前缀由协调者保证（见 A 的 SagaCoordinator）
            stock.adjustAvailable(productId, qty);
            txManager.commit(st);
            return result(msgId, "COMPENSATED", null);
        } catch (RuntimeException e) {
            rollback(st);
            throw e;
        }
    }

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
