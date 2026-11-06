package com.example.demo.txn.order.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * 本地消息表（outbox_message）访问。
 * 「订单 + 消息」必须由调用方在同一个本地事务里写入（见 LocalMessageService.placeOrder），
 * 本类只提供消息行的读写，不own事务边界——这是 Outbox 模式的要点：
 * 消息和业务数据的原子性靠共享同一个本地事务，不靠任何中间件。
 */
@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 在调用方事务内插入一条 PENDING 消息。 */
    public void insertPending(String msgId, String xid, String customerId, String productId,
                              int qty, int points) {
        jdbc.update("""
                        INSERT INTO outbox_message
                            (msg_id, xid, customer_id, product_id, qty, points, state)
                        VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
                        """,
                msgId, xid, customerId, productId, qty, points);
    }

    /** 取出到期可投递的 PENDING 消息（重投器每轮的输入）。 */
    public List<Map<String, Object>> duePending() {
        return jdbc.queryForList("""
                SELECT id, msg_id, xid, customer_id, product_id, qty, points, retry_count
                FROM outbox_message
                WHERE state = 'PENDING' AND next_retry_at <= NOW(3)
                ORDER BY id
                """);
    }

    /** 投递成功：消息置 SENT，不再重投。 */
    public void markSent(long id) {
        jdbc.update("UPDATE outbox_message SET state = 'SENT', sent_at = NOW(3), last_error = NULL WHERE id = ?", id);
    }

    /**
     * 投递失败：指数退避。第 n 次失败后等 min(2^n × 1s, 15s) 再试。
     * 重试超上限置 DEAD——最终一致不等于无限重试，扩散到底仍失败的消息
     * 必须有「停止并报警」的出口。
     */
    public void markFailed(long id, int retryCount, int maxRetries, String error) {
        if (retryCount >= maxRetries) {
            jdbc.update("UPDATE outbox_message SET state = 'DEAD', last_error = ? WHERE id = ?",
                    truncate(error), id);
        } else {
            // 退避基数 1 秒：n=1 → 2s, n=2 → 4s …，15s 封顶避免教程等待过长
            long backoffMs = Math.min((long) Math.pow(2, retryCount) * 1000, 15_000);
            jdbc.update("""
                            UPDATE outbox_message
                            SET retry_count = ?, next_retry_at = DATE_ADD(NOW(3), INTERVAL ? MICROSECOND),
                                last_error = ?
                            WHERE id = ?
                            """,
                    retryCount, backoffMs * 1000, truncate(error), id);
        }
    }

    public List<Map<String, Object>> recent(int limit) {
        return jdbc.queryForList("""
                SELECT msg_id, xid, state, retry_count, last_error, created_at, sent_at
                FROM outbox_message ORDER BY id DESC LIMIT ?
                """, limit);
    }

    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        // last_error 列宽 255：超长异常信息截断，保住 INSERT 不炸
        return s.length() <= 255 ? s : s.substring(0, 255);
    }
}
