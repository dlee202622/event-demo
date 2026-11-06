package com.example.demo.txn.inventory.core;

import com.example.demo.txn.inventory.repository.StockRepository;
import com.example.demo.txn.inventory.support.FaultRegistry;
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
 * 2PC 参与者：实现两阶段提交里 B 这一侧的 Prepare / Commit / Rollback。
 *
 * <p>关键实现：Prepare 从连接池借出一条【独占连接】并 setAutoCommit(false)，
 * 扣减库存、写 prepare 日志之后【不提交】，把这条连接存进内存 Map。
 * HTTP 请求返回后事务仍然打开、行锁仍然持有——直到协调者发来
 * Commit/Rollback 才在这条连接上执行 commit/rollback 并归还连接。
 * 这是 2PC「参与者持有资源」的最小可运行还原。</p>
 *
 * <p>为什么不用 PlatformTransactionManager：它的 TransactionStatus 绑定在
 * 创建线程的 ThreadLocal 上，Commit 作为第二个 HTTP 请求到达时是另一个线程，
 * cleanup 阶段会抛「No value for key bound to thread」。分布式事务的
 * 参与者必须跨线程持事务，裸 Connection 是唯一诚实的做法——
 * 这本身就是 2PC 的教学要点之一：参与者要为未决事务付出一条连接的代价。</p>
 *
 * <p>坑：未决事务每笔占一条池内连接，并发未决数超过 Hikari 上限（默认 10）
 * 后新的 Prepare 会在取连接处排队；服务重启丢失内存 Map，
 * 只能靠 txn_prepare_log 表人工决议（与真实 2PC 参与者恢复一致）。</p>
 */
@Service
public class TwoPcParticipant {

    /** 本服务的分支标识，教程里 A 的响应会逐分支展示。 */
    public static final String BRANCH = "inventory";

    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final FaultRegistry fault;

    /**
     * 未决事务登记表：xid → 独占连接与业务上下文。
     * in-doubt（悬而未决）的准确含义就是「存在于这个 Map 里、等协调者发话」。
     */
    private final Map<String, HoldingTx> pending = new ConcurrentHashMap<>();

    /** 一条被扣留的连接 + 操作它用的 JdbcTemplate（绑定在这条连接上）。 */
    private record HoldingTx(Connection con, JdbcTemplate txJdbc, String productId, int qty) {
    }

    public TwoPcParticipant(DataSource dataSource, JdbcTemplate jdbc, FaultRegistry fault) {
        this.dataSource = dataSource;
        this.jdbc = jdbc;
        this.fault = fault;
    }

    /**
     * 阶段一投票：能扣就扣（先锁行再改），然后把连接扣在手里等决议。
     *
     * @return vote=NO 时带 reason，A 只对投 YES 的分支发送二阶段请求
     */
    public Map<String, Object> prepare(String xid, String productId, int qty) {
        fault.check("inventory 2PC prepare");
        Connection con;
        try {
            con = dataSource.getConnection();
            con.setAutoCommit(false); // 手动事务：生死由 commit/rollback 决定，不由请求结束决定
        } catch (SQLException e) {
            throw new IllegalStateException("取连接失败（池可能被未决事务占满）", e);
        }
        try {
            // 所有 SQL 必须走绑定这条连接的 JdbcTemplate：
            // 用普通 jdbc（按线程取连接）会拿到别的连接，事务边界就散了
            JdbcTemplate txJdbc = new JdbcTemplate(new SingleConnectionDataSource(con, true));
            // 锁顺序固定「先锁库存行」：保证同一商品并发的 Prepare/Try 串行化，
            // 避免两个事务同时通过 available 检查后超扣
            Integer available = txJdbc.queryForObject(
                    "SELECT available FROM stock WHERE product_id = ? FOR UPDATE", Integer.class, productId);
            if (available == null) {
                throw new IllegalArgumentException("商品不存在：" + productId);
            }
            if (available < qty) {
                throw new IllegalStateException("库存不足：available=" + available + " 需要=" + qty);
            }
            txJdbc.update("UPDATE stock SET available = available - (?) WHERE product_id = ?", qty, productId);
            // Prepare 日志与扣减在同一事务：Commit 时一起生效；若参与者宕机，
            // 这张表里残留的 PREPARED 记录就是「我扣了但没决议」的铁证
            txJdbc.update(
                    "INSERT INTO txn_prepare_log (xid, state, product_id, qty) VALUES (?, 'PREPARED', ?, ?)",
                    xid, productId, qty);
            // 核心：不 commit！把连接存起来，让锁跨 HTTP 请求存活
            pending.put(xid, new HoldingTx(con, txJdbc, productId, qty));
            return vote(xid, "YES", null, productId, qty);
        } catch (RuntimeException e) {
            // 投 NO 前必须先回滚并归还连接：参与者不能留下半截事务污染库存
            closeWith(con, true);
            if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) {
                return vote(xid, "NO", e.getMessage(), productId, qty);
            }
            throw e; // 非业务异常照常上抛，HTTP 层映射 500
        }
    }

    /**
     * 阶段二：提交。收到它的前提是所有参与者都投了 YES（全局提交保证）。
     * 连接提交并归还池后，Prepare 日志改标 COMMITTED——这个 UPDATE 在新连接上
     * 自动提交，它只是给人看的审计记录，不再参与原子性。
     */
    public Map<String, Object> commit(String xid) {
        HoldingTx tx = pending.remove(xid);
        if (tx == null) {
            // 出现即协议被破坏（重复决议 / 协调者记错分支），明确报错而不是静默成功
            throw new IllegalStateException("xid=" + xid + " 无未决事务：可能已决议或从未 Prepare");
        }
        try {
            tx.con().commit();
        } catch (SQLException e) {
            throw new IllegalStateException("提交失败：" + xid, e);
        } finally {
            closeWith(tx.con(), true);
        }
        jdbc.update("UPDATE txn_prepare_log SET state = 'COMMITTED', decided_at = NOW(3) WHERE xid = ?", xid);
        return vote(xid, "COMMITTED", null, tx.productId(), tx.qty());
    }

    /**
     * 阶段二：回滚。把 Prepare 扣掉的库存加回去。
     * 这是 2PC 比 Saga「干净」的地方：参与者从未对外提交过，回滚等于无事发生。
     */
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
            closeWith(tx.con(), true);
        }
        jdbc.update("UPDATE txn_prepare_log SET state = 'ROLLED_BACK', decided_at = NOW(3) WHERE xid = ?", xid);
        return vote(xid, "ROLLED_BACK", null, tx.productId(), tx.qty());
    }

    /** 协调者宕机后的现场还原：内存未决数 + 日志表明细，人工决议前的唯一信息来源。 */
    public Map<String, Object> status() {
        List<Map<String, Object>> logs = jdbc.queryForList(
                "SELECT xid, state, product_id, qty, created_at FROM txn_prepare_log ORDER BY created_at");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branch", BRANCH);
        out.put("pendingTxCount", pending.size());
        out.put("pendingXids", pending.keySet());
        out.put("prepareLogs", logs);
        return out;
    }

    /** 归还连接：先恢复 autoCommit（池对连接状态有假设），再 close（= 还回池）。 */
    private void closeWith(Connection con, boolean resetAutocommit) {
        try {
            if (resetAutocommit) {
                con.setAutoCommit(true);
            }
            con.close();
        } catch (SQLException ignored) {
            // 归还失败只影响池容量，不影响协议正确性，静默即可
        }
    }

    private Map<String, Object> vote(String xid, String result, String reason, String productId, int qty) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branch", BRANCH);
        out.put("xid", xid);
        out.put("result", result);
        out.put("productId", productId);
        out.put("qty", qty);
        if (reason != null) {
            out.put("reason", reason);
        }
        return out;
    }
}
