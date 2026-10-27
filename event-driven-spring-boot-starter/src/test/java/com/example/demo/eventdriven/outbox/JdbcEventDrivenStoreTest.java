package com.example.demo.eventdriven.outbox;

import com.example.demo.eventdriven.idempotency.JdbcProcessedEventRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
class JdbcEventDrivenStoreTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("starter_test")
            .withUsername("test")
            .withPassword("test");

    private static JdbcOutboxStore outboxStore;
    private static JdbcProcessedEventRegistry registry;

    @BeforeAll
    static void setUp() throws Exception {
        DataSource dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/event-driven/V0__event_driven_infrastructure.sql"));
        }
        outboxStore = new JdbcOutboxStore(jdbcTemplate, dataSource);
        registry = new JdbcProcessedEventRegistry(jdbcTemplate);
    }

    @BeforeEach
    void clean() {
        outboxStore.clear();
        registry.clear();
    }

    @Test
    @DisplayName("认领：只取 PENDING，状态置为 SENDING，受 limit 限制")
    void claimPending_shouldMoveEventsToSending() {
        outboxStore.append(event("id-1", "event-1", Instant.now().minusSeconds(1)));
        outboxStore.append(event("id-2", "event-2", Instant.now()));

        List<OutboxEvent> claimed = outboxStore.claimPending(1);

        assertEquals(1, claimed.size());
        assertEquals("event-1", claimed.get(0).eventId());
        assertEquals(OutboxStatus.SENDING, claimed.get(0).status());

        // 已被认领的事件不会再次出现在认领结果里
        assertTrue(outboxStore.claimPending(10).stream()
                .noneMatch(event -> event.id().equals("id-1")));
    }

    @Test
    @DisplayName("投递成功：SENDING → SENT，pendingCount 归零")
    void markDelivered_shouldMarkEventSent() {
        outboxStore.append(event("id-1", "event-1", Instant.now()));
        outboxStore.claimPending(10);

        outboxStore.markDelivered("id-1");

        assertEquals(OutboxStatus.SENT, outboxStore.findAll().get(0).status());
        assertEquals(0, outboxStore.pendingCount());
    }

    @Test
    @DisplayName("投递失败：SENDING → PENDING，可被再次认领")
    void markFailed_shouldReturnEventToPending() {
        outboxStore.append(event("id-1", "event-1", Instant.now()));
        outboxStore.claimPending(10);

        outboxStore.markFailed("id-1");

        assertEquals(1, outboxStore.pendingCount());
        assertEquals(1, outboxStore.claimPending(10).size());
    }

    @Test
    @DisplayName("幂等注册表：首次登记返回 true，重复登记返回 false")
    void markIfAbsent_shouldRejectDuplicateEventId() {
        assertTrue(registry.markIfAbsent("event-1"));
        assertFalse(registry.markIfAbsent("event-1"));
        assertTrue(registry.isProcessed("event-1"));
        assertEquals(1, registry.size());
    }

    private static OutboxEvent event(String id, String eventId, Instant occurredOn) {
        return new OutboxEvent(id, eventId, "TestEvent", "agg-1", "{}", occurredOn);
    }
}
