package com.example.demo.txn.order;

import com.example.demo.txn.order.core.LocalMessageService;
import com.example.demo.txn.order.repository.OrderRepository;
import com.example.demo.txn.order.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A 服务的本地逻辑测试（不依赖 B/C 进程）：
 * 重点验证「订单 + 消息同事务落库」这个全方案唯一的强一致保证点，
 * 以及重投退避与 DEAD 判定的纯 DB 行为。跨服务编排的端到端验证走 README 实测。
 */
@SpringBootTest
@Testcontainers
class LocalMessageTests {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        // 单测不依赖 Seata TC，也不真的发起对 B/C 的 HTTP 调用
        registry.add("seata.enabled", () -> "false");
        // 重投间隔拉到 1 小时：测试进程里不真的发起对 B/C 的 HTTP 调用
        registry.add("demo.txn.order.localmsg.relay-interval-ms", () -> "3600000");
    }

    @Autowired LocalMessageService localMessage;
    @Autowired OrderRepository orders;
    @Autowired OutboxRepository outbox;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outbox_message");
        jdbc.update("DELETE FROM orders");
    }

    @Test
    void orderAndMessageAreWrittenAtomically() {
        Map<String, Object> out = localMessage.placeOrder(1, 10);
        String xid = (String) out.get("xid");
        assertEquals("INIT", out.get("orderStatus"));
        // 同一事务的两行都可见，且订单状态与消息状态互相对应
        Map<String, Object> order = orders.findByXid(xid);
        assertNotNull(order);
        assertEquals("INIT", order.get("status"));
        var msgs = jdbc.queryForList("SELECT * FROM outbox_message WHERE xid = ?", xid);
        assertEquals(1, msgs.size());
        assertEquals("PENDING", msgs.get(0).get("state"));
    }

    @Test
    void failedRelayBacksOffThenDies() {
        localMessage.placeOrder(1, 10);
        var msg = outbox.duePending().get(0);
        long id = ((Number) msg.get("id")).longValue();

        // 模拟投递失败的退避：retry_count 递增、next_retry_at 后移
        outbox.markFailed(id, 1, 10, "connect refused");
        var row1 = jdbc.queryForList("SELECT state, retry_count FROM outbox_message WHERE id = ?", id).get(0);
        assertEquals("PENDING", row1.get("state"));
        assertEquals(1, ((Number) row1.get("retry_count")).intValue());
        // 第 1 次失败后退避 2 秒：此刻还没到期，不应出现在投递队列里
        assertTrue(outbox.duePending().stream().noneMatch(m -> ((Number) m.get("id")).longValue() == id),
                "退避窗口内不应可投递");

        // 超过上限：消息 DEAD，不再进入投递队列
        outbox.markFailed(id, 10, 10, "connect refused");
        var row2 = jdbc.queryForList("SELECT state FROM outbox_message WHERE id = ?", id).get(0);
        assertEquals("DEAD", row2.get("state"));
    }

    @Test
    void orderRepositoryTracksStatusTransitions() {
        orders.insert("x-1", "2PC", "C-1001", "P-1001", 1, 10, "PREPARING", null);
        orders.updateStatus("x-1", "CONFIRMED", "全员 YES，全局提交");
        assertEquals("CONFIRMED", orders.findByXid("x-1").get("status"));
    }
}
