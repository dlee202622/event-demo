package com.example.demo.eventdriven.idempotency;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MySQL 版幂等注册表。
 *
 * <p>markIfAbsent 依赖主键 + INSERT IGNORE：并发消费同一 eventId 时只有一个能拿到 1 行影响数。</p>
 */
public class JdbcProcessedEventRegistry implements ProcessedEventRegistry {

    private final JdbcTemplate jdbcTemplate;

    public JdbcProcessedEventRegistry(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean markIfAbsent(String eventId) {
        return jdbcTemplate.update(
                "INSERT IGNORE INTO processed_event (event_id) VALUES (?)", eventId) > 0;
    }

    @Override
    public boolean isProcessed(String eventId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM processed_event WHERE event_id = ?)",
                Boolean.class, eventId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public int size() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM processed_event", Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public void clear() {
        jdbcTemplate.update("DELETE FROM processed_event");
    }
}
