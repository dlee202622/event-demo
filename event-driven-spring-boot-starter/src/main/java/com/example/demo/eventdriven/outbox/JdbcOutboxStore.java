package com.example.demo.eventdriven.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * MySQL 版发件箱存储。
 *
 * <p>append 参与调用方事务（与业务数据同时提交）；认领使用独立事务 +
 * SELECT ... FOR UPDATE SKIP LOCKED，保证多实例下同一条事件只被认领一次。</p>
 */
public class JdbcOutboxStore implements OutboxStore {

    private static final RowMapper<OutboxEvent> ROW_MAPPER = (rs, rowNum) -> new OutboxEvent(
            rs.getString("id"),
            rs.getString("event_id"),
            rs.getString("event_type"),
            rs.getString("aggregate_id"),
            rs.getString("payload"),
            rs.getTimestamp("occurred_on").toInstant(),
            OutboxStatus.valueOf(rs.getString("status"))
    );

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public JdbcOutboxStore(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        TransactionTemplate template = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactionTemplate = template;
    }

    @Override
    public void append(OutboxEvent event) {
        jdbcTemplate.update(
                "INSERT INTO outbox_event (id, event_id, event_type, aggregate_id, payload, occurred_on, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                event.id(),
                event.eventId(),
                event.eventType(),
                event.aggregateId(),
                event.payload(),
                Timestamp.from(event.occurredOn()),
                OutboxStatus.PENDING.name());
    }

    @Override
    public List<OutboxEvent> claimPending(int limit) {
        return transactionTemplate.execute(status -> {
            List<OutboxEvent> batch = jdbcTemplate.query(
                    "SELECT id, event_id, event_type, aggregate_id, payload, occurred_on, status "
                            + "FROM outbox_event WHERE status = 'PENDING' "
                            + "ORDER BY occurred_on, id LIMIT ? FOR UPDATE SKIP LOCKED",
                    ROW_MAPPER,
                    limit);
            if (batch.isEmpty()) {
                return List.of();
            }
            List<Object[]> claimedIds = batch.stream()
                    .map(event -> new Object[]{event.id()})
                    .toList();
            jdbcTemplate.batchUpdate(
                    "UPDATE outbox_event SET status = 'SENDING' WHERE id = ?", claimedIds);
            batch.forEach(OutboxEvent::markSending);
            return batch;
        });
    }

    @Override
    public void markDelivered(String outboxId) {
        jdbcTemplate.update(
                "UPDATE outbox_event SET status = 'SENT', delivered_at = ? WHERE id = ?",
                Timestamp.from(Instant.now()), outboxId);
    }

    @Override
    public void markFailed(String outboxId) {
        jdbcTemplate.update("UPDATE outbox_event SET status = 'PENDING' WHERE id = ?", outboxId);
    }

    @Override
    public List<OutboxEvent> findAll() {
        return jdbcTemplate.query(
                "SELECT id, event_id, event_type, aggregate_id, payload, occurred_on, status "
                        + "FROM outbox_event ORDER BY occurred_on, id",
                ROW_MAPPER);
    }

    @Override
    public int pendingCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE status <> 'SENT'", Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public int size() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event", Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public void clear() {
        jdbcTemplate.update("DELETE FROM outbox_event");
    }
}
