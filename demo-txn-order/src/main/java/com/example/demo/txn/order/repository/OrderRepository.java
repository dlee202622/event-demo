package com.example.demo.txn.order.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单表访问：协调者的簿记。status 的流转是每个方案主线的一部分
 * （PREPARING→CONFIRMED/CANCELLED/IN_DOUBT 等），由各协调器显式推进。
 */
@Repository
public class OrderRepository {

    private final JdbcTemplate jdbc;

    public OrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 新建订单簿记。scheme 记录用的是哪种方案，便于实验后按方案筛选对比。 */
    public void insert(String xid, String scheme, String customerId, String productId,
                       int qty, int points, String status, String note) {
        jdbc.update("""
                        INSERT INTO orders (xid, scheme, customer_id, product_id, qty, points, status, note)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                xid, scheme, customerId, productId, qty, points, status, note);
    }

    /** 推进订单状态（各协调器在每个协议阶段结束后调用）。 */
    public void updateStatus(String xid, String status, String note) {
        jdbc.update("UPDATE orders SET status = ?, note = ? WHERE xid = ?", status, note, xid);
    }

    public Map<String, Object> findByXid(String xid) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM orders WHERE xid = ?", xid);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 最近订单：教程里实验后核对各方案最终状态用。 */
    public List<Map<String, Object>> recent(int limit) {
        return jdbc.queryForList(
                "SELECT xid, scheme, status, note, qty, points, created_at FROM orders ORDER BY id DESC LIMIT ?",
                limit);
    }
}
