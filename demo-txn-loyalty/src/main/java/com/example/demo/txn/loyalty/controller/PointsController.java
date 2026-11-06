package com.example.demo.txn.loyalty.controller;

import com.example.demo.txn.loyalty.repository.PointsRepository;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 积分查询 / 锁观测 / 流水 / 复位接口。
 *
 * <p>提供两种读法，专门用来观察 2PC Prepare 期间的锁行为：</p>
 * <ul>
 *   <li>GET /api/points/{id}——MVCC 快照读，永远不被未提交事务阻塞，
 *       但也永远看不到未提交的扣减（读到的是旧值）；</li>
 *   <li>POST /api/points/{id}/lock-check——FOR UPDATE NOWAIT 悲观读，
 *       撞上 Prepare 持有的行锁时立刻失败返回。</li>
 * </ul>
 *
 * <p>两个接口组合出 2PC 隔离性实验的完整观测面：
 * 「普通读看到旧值、锁定读被挡」同时发生。</p>
 */
@RestController
@RequestMapping("/api/points")
public class PointsController {

    private final PointsRepository points;
    private final JdbcTemplate jdbc;

    public PointsController(PointsRepository points, JdbcTemplate jdbc) {
        this.points = points;
        this.jdbc = jdbc;
    }

    /** 快照读当前余额。2PC Prepare 未决时 balance 仍是旧值（未提交不可见）。 */
    @GetMapping("/{customerId}")
    public Map<String, Object> get(@PathVariable String customerId) {
        // 账户不存在显示为 0：教学演示中保持接口总是可用，避免误判为服务故障
        Map<String, Object> out = points.findPlain(customerId)
                .orElseGet(() -> {
                    Map<String, Object> empty = new LinkedHashMap<>();
                    empty.put("customerId", customerId);
                    empty.put("balance", 0);
                    empty.put("frozen", 0);
                    return empty;
                });
        // 标注读法：教程靠 readMode 字段区分「快照读」和「锁定读」两列结果
        out.put("readMode", "snapshot(MVCC)");
        return out;
    }

    /**
     * 锁定读探测：对同一行执行 SELECT ... FOR UPDATE NOWAIT。
     * NOWAIT 而不是等待：Prepare 持锁期间调用它立即报错返回，
     * 教程里用「失败得有多快」来证明锁真的滞留了。
     */
    @PostMapping("/{customerId}/lock-check")
    public Map<String, Object> lockCheck(@PathVariable String customerId) {
        long begin = System.nanoTime();
        try {
            int balance = points.lockBalanceNowait(customerId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("locked", true);
            out.put("balance", balance);
            out.put("costMs", (System.nanoTime() - begin) / 1_000_000);
            out.put("readMode", "FOR UPDATE NOWAIT");
            return out;
        } catch (PessimisticLockingFailureException | UncategorizedSQLException e) {
            // NOWAIT 拿不到锁时 MySQL 报 3572/1205 类错误，Spring 翻译成锁获取失败——
            // 这不是异常路径，而是探测的「正常命中」：锁确实被未决事务持有
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("locked", false);
            out.put("reason", "行被未决的 Prepare 事务持有（NOWAIT 立即失败）");
            out.put("costMs", (System.nanoTime() - begin) / 1_000_000);
            out.put("readMode", "FOR UPDATE NOWAIT");
            return out;
        }
    }

    /** 流水查询：Saga / 本地消息表实验后核对 DEDUCT 与 COMPENSATE 的笔数。 */
    @GetMapping("/{customerId}/ledger")
    public Map<String, Object> ledger(@PathVariable String customerId) {
        return Map.of(
                "customerId", customerId,
                "entries", jdbc.queryForList(
                        "SELECT delta, type, msg_id, created_at FROM points_ledger WHERE customer_id = ? ORDER BY id",
                        customerId));
    }

    /** 实验复位：余额拨回指定值并清空流水，避免上一轮实验残留影响下一轮。 */
    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestParam(defaultValue = "C-1001") String customerId,
                                     @RequestParam(defaultValue = "100") int balance) {
        points.reset(customerId, balance);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reset", true);
        out.put("customerId", customerId);
        out.put("balance", balance);
        out.put("frozen", 0);
        return out;
    }
}
