package com.example.demo.loyalty.infrastructure.persistence;

import com.example.demo.loyalty.domain.PointsRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.TreeMap;

/**
 * 积分仓储的 JDBC 实现。
 *
 * <p>用 ON DUPLICATE KEY UPDATE 做单条 SQL 原子累加，避免「先查后写」的并发丢更新。</p>
 */
@Repository
public class JdbcPointsRepository implements PointsRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcPointsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int add(String customerId, int points) {
        jdbcTemplate.update(
                "INSERT INTO loyalty_points (customer_id, points) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE points = loyalty_points.points + ?",
                customerId, points, points);
        return pointsOf(customerId);
    }

    @Override
    public int pointsOf(String customerId) {
        Integer points = jdbcTemplate.query(
                "SELECT points FROM loyalty_points WHERE customer_id = ?",
                rs -> rs.next() ? rs.getInt(1) : 0,
                customerId);
        return points == null ? 0 : points;
    }

    @Override
    public Map<String, Integer> findAll() {
        Map<String, Integer> snapshot = new TreeMap<>();
        jdbcTemplate.query("SELECT customer_id, points FROM loyalty_points",
                rs -> {
                    snapshot.put(rs.getString("customer_id"), rs.getInt("points"));
                });
        return snapshot;
    }
}
