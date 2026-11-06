package com.example.demo.txn.inventory.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 库存表（stock）访问层：只放「纯库存读写」这一件事。
 *
 * <p>刻意保持薄：2PC / TCC / Saga 各自的扣减 SQL 不收敛到这里，
 * 而是写在各自的核心类里、和协议日志表同事务执行——教学目标是让读者
 * 在一处代码里同时看到「业务写 + 协议写」的事务边界，拆开就看不见了。</p>
 *
 * <p>坑：{@link #lockAvailable} 使用 SELECT ... FOR UPDATE，必须在事务内调用，
 * 事务外的悲观读等于没锁（autocommit 下锁即刻释放）。</p>
 */
@Repository
public class StockRepository {

    private final JdbcTemplate jdbc;

    public StockRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 普通一致性读（MVCC 快照）：不会被未提交的 Prepare 扣减挡住，教程用它展示「脏读不会发生」。 */
    public Optional<Map<String, Object>> findPlain(String productId) {
        return jdbc.query(
                "SELECT product_id, available, frozen FROM stock WHERE product_id = ?",
                (rs, i) -> row(rs.getString("product_id"), rs.getInt("available"), rs.getInt("frozen")),
                productId).stream().findFirst();
    }

    /**
     * 悲观锁读：返回当前可售库存并锁住这一行，直到调用方事务结束才释放。
     * 2PC 的 Prepare、TCC 的 Try 都靠它把并发请求串行化在「查-改」之间。
     */
    public int lockAvailable(String productId) {
        Integer v = jdbc.queryForObject(
                "SELECT available FROM stock WHERE product_id = ? FOR UPDATE",
                Integer.class, productId);
        if (v == null) {
            // 商品不存在按 0 处理而不是 NPE：让上层统一走「库存不足」分支
            throw new IllegalArgumentException("商品不存在：" + productId);
        }
        return v;
    }

    /**
     * NOWAIT 版锁定读：拿不到锁立即报错（MySQL 8 错误 3572），
     * 供 /api/stock/{id}/lock-check 探测「行是否被未决 Prepare 持有」。
     * 不用它的话探测请求会默认等 50 秒（innodb_lock_wait_timeout），实验没法看。
     */
    public int lockAvailableNowait(String productId) {
        Integer v = jdbc.queryForObject(
                "SELECT available FROM stock WHERE product_id = ? FOR UPDATE NOWAIT",
                Integer.class, productId);
        if (v == null) {
            throw new IllegalArgumentException("商品不存在：" + productId);
        }
        return v;
    }

    /** 可售库存增减：delta 为负数表示扣减。必须已在锁行之后的事务内调用。 */
    public int adjustAvailable(String productId, int delta) {
        return jdbc.update("UPDATE stock SET available = available + (?) WHERE product_id = ?", delta, productId);
    }

    /** 冻结额度增减：TCC Try 时 +qty（从 available 转入），Confirm 时 -qty（永久扣除）。 */
    public int adjustFrozen(String productId, int delta) {
        return jdbc.update("UPDATE stock SET frozen = frozen + (?) WHERE product_id = ?", delta, productId);
    }

    /** 实验复位：把库存拨回指定值，避免上一轮实验的残留影响下一轮结果。 */
    public int reset(String productId, int available, int frozen) {
        return jdbc.update("UPDATE stock SET available = ?, frozen = ? WHERE product_id = ?",
                available, frozen, productId);
    }

    private Map<String, Object> row(String productId, int available, int frozen) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", productId);
        m.put("available", available);
        m.put("frozen", frozen);
        return m;
    }
}
