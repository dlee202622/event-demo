package com.example.demo.infrastructure.event;

import com.example.demo.eventdriven.domain.DomainEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;

/**
 * 领域事件存储（MySQL 版）。
 *
 * <p>用于审计、排障和事件溯源；与 Outbox 不同，它只做本地留存，不参与投递。</p>
 */
@Component
public class DomainEventStore {

    private final JdbcTemplate jdbcTemplate;
    private final DomainEventCodec codec;
    private final RowMapper<DomainEvent> rowMapper;

    public DomainEventStore(JdbcTemplate jdbcTemplate, DomainEventCodec codec) {
        this.jdbcTemplate = jdbcTemplate;
        this.codec = codec;
        this.rowMapper = (rs, rowNum) -> codec.decode(rs.getString("event_type"), rs.getString("payload"));
    }

    /**
     * 存储事件（参与调用方事务）。
     */
    public void append(DomainEvent event) {
        jdbcTemplate.update(
                "INSERT INTO domain_event (event_id, event_type, aggregate_id, payload, occurred_on) "
                        + "VALUES (?, ?, ?, ?, ?)",
                event.eventId(),
                event.eventType(),
                event.aggregateId(),
                codec.encode(event),
                Timestamp.from(event.occurredOn()));
    }

    /**
     * 获取指定聚合根的所有事件（按写入顺序）。
     */
    public List<DomainEvent> eventsOf(String aggregateId) {
        return jdbcTemplate.query(
                "SELECT event_type, payload FROM domain_event WHERE aggregate_id = ? ORDER BY seq",
                rowMapper, aggregateId);
    }

    /**
     * 获取所有事件（按写入顺序）。
     */
    public List<DomainEvent> findAll() {
        return jdbcTemplate.query(
                "SELECT event_type, payload FROM domain_event ORDER BY seq", rowMapper);
    }

    /**
     * 获取所有事件（别名方法）。
     */
    public List<DomainEvent> allEvents() {
        return findAll();
    }

    /**
     * 获取事件总数。
     */
    public int size() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM domain_event", Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 清空所有事件（仅测试用）。
     */
    public void clear() {
        jdbcTemplate.update("DELETE FROM domain_event");
    }
}
