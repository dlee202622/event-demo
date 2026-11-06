package com.example.demo.txn.loyalty.core;

import com.example.demo.txn.loyalty.repository.PointsRepository;
import com.example.demo.txn.loyalty.support.FaultRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 2PC 参与者（积分侧）：Prepare 后独占连接保持事务打开，等协调者二阶段决议。
 * 与 B 的实现完全对称，业务对象从库存行换成积分账户。
 *
 * <p>连接独占而非 PlatformTransactionManager 的原因（B 侧同类有详细注释）：
 * Commit 是第二个 HTTP 请求、另一个线程，Spring 事务管理器的 ThreadLocal
 * 绑定模型管不到跨线程的事务——2PC 参与者必须自己抱住这条连接。</p>
 *
 * <p>教程价值：B、C 两个参与者的未决事务可以分别观测——
 * 协调者「Prepare 完 B、还没来得及 Prepare C」就宕机时，
 * B 有未决事务而 C 干干净净，这正是两阶段提交里「决议不完整」的典型现场。</p>
 */
@Service
public class TwoPcParticipant {

    public static final String BRANCH = "loyalty";

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final FaultRegistry fault;

    private final Map<String, HoldingTx> pending = new ConcurrentHashMap<>();

    private record HoldingTx(Connection con, String customerId, int points) {
    }

    public TwoPcParticipant(DataSource dataSource, JdbcTemplate jdbc, FaultRegistry fault) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.fault = fault;
    }

    /** 阶段一投票：锁账户、扣积分、写 Prepare 日志（三者同事务，保持打开等决议）。 */
    public Map<String, Object> prepare(String xid, String customerId, int pointsToDeduct) {
        fault.check("loyalty 2PC prepare");
        Connection con;
        try {
            con = dataSource.getConnection();
            con.setAutoCommit(false);
        } catch (SQLException e) {
            throw new IllegalStateException("取连接失败（池可能被未决事务占满）", e);
        }
        try {
            JdbcTemplate txJdbc = new JdbcTemplate(new SingleConnectionDataSource(con, true));
            Integer balance = txJdbc.queryForObject(
                    "SELECT balance FROM points_account WHERE customer_id = ? FOR UPDATE",
                    Integer.class, customerId);
            int current = balance == null ? 0 : balance;
            if (current < pointsToDeduct) {
                throw new IllegalStateException("积分不足：balance=" + current + " 需要=" + pointsToDeduct);
            }
            txJdbc.update("UPDATE points_account SET balance = balance - (?) WHERE customer_id = ?",
                    pointsToDeduct, customerId);
            txJdbc.update(
                    "INSERT INTO txn_prepare_log (xid, state, customer_id, points) VALUES (?, 'PREPARED', ?, ?)",
                    xid, customerId, pointsToDeduct);
            // 不提交：连接与未提交变更跨 HTTP 请求存活，等待二阶段
            pending.put(xid, new HoldingTx(con, customerId, pointsToDeduct));
            return vote(xid, "YES", null, customerId, pointsToDeduct);
        } catch (RuntimeException e) {
            closeWith(con);
            if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
                return vote(xid, "NO", e.getMessage(), customerId, pointsToDeduct);
            }
            throw e;
        }
    }

    /** 阶段二提交：未决事务生效，余额扣减对所有人可见。 */
    public Map<String, Object> commit(String xid) {
        HoldingTx tx = pending.remove(xid);
        if (tx == null) {
            throw new IllegalStateException("xid=" + xid + " 无未决事务：可能已决议或从未 Prepare");
        }
        try {
            tx.con().commit();
        } catch (SQLException e) {
            throw new IllegalStateException("提交失败：" + xid, e);
        } finally {
            closeWith(tx.con());
        }
        jdbc.update("UPDATE txn_prepare_log SET state = 'COMMITTED', decided_at = NOW(3) WHERE xid = ?", xid);
        return vote(xid, "COMMITTED", null, tx.customerId(), tx.points());
    }

    /** 阶段二回滚：未提交事务直接丢弃，余额回到 Prepare 前的值。 */
    public Map<String, Object> rollback(String xid) {
        HoldingTx tx = pending.remove(xid);
        if (tx == null) {
            throw new IllegalStateException("xid=" + xid + " 无未决事务：可能已决议或从未 Prepare");
        }
        try {
            tx.con().rollback();
        } catch (SQLException e) {
            throw new IllegalStateException("回滚失败：" + xid, e);
        } finally {
            closeWith(tx.con());
        }
        jdbc.update("UPDATE txn_prepare_log SET state = 'ROLLED_BACK', decided_at = NOW(3) WHERE xid = ?", xid);
        return vote(xid, "ROLLED_BACK", null, tx.customerId(), tx.points());
    }

    /** 未决事务 + Prepare 日志现场。 */
    public Map<String, Object> status() {
        List<Map<String, Object>> logs = jdbc.queryForList(
                "SELECT xid, state, customer_id, points, created_at FROM txn_prepare_log ORDER BY created_at");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branch", BRANCH);
        out.put("pendingTxCount", pending.size());
        out.put("pendingXids", pending.keySet());
        out.put("prepareLogs", logs);
        return out;
    }

    /** 恢复 autoCommit 再归还连接：池对借出连接的状态有假设，违背了会污染后续借出者。 */
    private void closeWith(Connection con) {
        try {
            con.setAutoCommit(true);
            con.close();
        } catch (SQLException ignored) {
            // 归还失败只影响池容量，不影响协议正确性
        }
    }

    private Map<String, Object> vote(String xid, String result, String reason, String customerId, int pointsToDeduct) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branch", BRANCH);
        out.put("xid", xid);
        out.put("result", result);
        out.put("customerId", customerId);
        out.put("points", pointsToDeduct);
        if (reason != null) {
            out.put("reason", reason);
        }
        return out;
    }
}
