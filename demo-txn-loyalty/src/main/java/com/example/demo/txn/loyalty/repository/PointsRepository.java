package com.example.demo.txn.loyalty.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 积分账户（points_account）访问层：纯账户读写，协议逻辑在 core 包各参与者里。
 *
 * <p>坑：{@link #lockBalance} 的 FOR UPDATE 必须在事务内调用；
 * 账户不存在的兜底策略与 B 不同——这里返回 0 让上层统一走「积分不足」，
 * 而不是抛「账户不存在」，避免教程实验里 C-1001 拼写错误引发误判。</p>
 */
@Repository
public class PointsRepository {

    private final JdbcTemplate jdbc;

    public PointsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** MVCC 快照读：看不到未提交的扣减，2PC Prepare 期间读到的是旧余额。 */
    public Optional<Map<String, Object>> findPlain(String customerId) {
        return jdbc.query(
                "SELECT customer_id, balance, frozen FROM points_account WHERE customer_id = ?",
                (rs, i) -> row(rs.getString("customer_id"), rs.getInt("balance"), rs.getInt("frozen")),
                customerId).stream().findFirst();
    }

    /** 悲观锁读余额。账户不存在时返回 0（配合上层「余额不足」分支），并锁住间隙防并发插入。 */
    public int lockBalance(String customerId) {
        Integer v = jdbc.queryForObject(
                "SELECT balance FROM points_account WHERE customer_id = ? FOR UPDATE",
                Integer.class, customerId);
        return v == null ? 0 : v;
    }

    /** NOWAIT 版锁定读：拿不到锁立即报错，供锁探测接口使用。 */
    public int lockBalanceNowait(String customerId) {
        Integer v = jdbc.queryForObject(
                "SELECT balance FROM points_account WHERE customer_id = ? FOR UPDATE NOWAIT",
                Integer.class, customerId);
        return v == null ? 0 : v;
    }

    public int adjustBalance(String customerId, int delta) {
        return jdbc.update("UPDATE points_account SET balance = balance + (?) WHERE customer_id = ?",
                delta, customerId);
    }

    public int adjustFrozen(String customerId, int delta) {
        return jdbc.update("UPDATE points_account SET frozen = frozen + (?) WHERE customer_id = ?",
                delta, customerId);
    }

    /** 流水记账：Saga / 本地消息表路径每次变动记一条，type 区分扣减与补偿。 */
    public void recordLedger(String customerId, int delta, String type, String msgId) {
        jdbc.update(
                "INSERT INTO points_ledger (customer_id, delta, type, msg_id) VALUES (?, ?, ?, ?)",
                customerId, delta, type, msgId);
    }

    /** 实验复位：余额拨回指定值并清空流水。 */
    public int reset(String customerId, int balance) {
        jdbc.update("DELETE FROM points_ledger WHERE customer_id = ?", customerId);
        return jdbc.update("UPDATE points_account SET balance = ?, frozen = 0 WHERE customer_id = ?",
                balance, customerId);
    }

    private Map<String, Object> row(String customerId, int balance, int frozen) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customerId", customerId);
        m.put("balance", balance);
        m.put("frozen", frozen);
        return m;
    }
}
