package com.example.demo.txn.inventory.controller;

import com.example.demo.txn.inventory.repository.StockRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 库存查询 / 锁观测接口。
 *
 * <p>提供两种读法，专门用来观察 2PC Prepare 期间的锁行为：</p>
 * <ul>
 *   <li>GET /api/stock/{id}——MVCC 快照读，永远不被未提交事务阻塞，
 *       但也永远看不到未提交的扣减（读到的是旧值）；</li>
 *   <li>POST /api/stock/{id}/lock-check——FOR UPDATE 悲观读，
 *       撞上 Prepare 持有的行锁时立刻以「NOWAIT」失败返回。</li>
 * </ul>
 *
 * <p>两个接口组合出 2PC 隔离性实验的完整观测面：
 * 「普通读看到旧值、锁定读被挡」同时发生。</p>
 */
@RestController
@RequestMapping("/api/stock")
public class StockController {

    private final StockRepository stock;

    public StockController(StockRepository stock) {
        this.stock = stock;
    }

    /** 快照读当前库存。2PC Prepare 未决时 available 仍是旧值（未提交不可见）。 */
    @GetMapping("/{productId}")
    public Map<String, Object> get(@PathVariable String productId) {
        Map<String, Object> out = new LinkedHashMap<>(stock.findPlain(productId)
                .orElseThrow(() -> new IllegalArgumentException("商品不存在：" + productId)));
        out.put("readMode", "snapshot(MVCC)");
        return out;
    }

    /**
     * 锁定读探测：对同一行执行 SELECT ... FOR UPDATE NOWAIT。
     * NOWAIT 而不是等待：Prepare 持锁期间调用它立即报错返回，
     * 教程里用「失败得有多快」来证明锁真的滞留了。
     */
    @PostMapping("/{productId}/lock-check")
    public Map<String, Object> lockCheck(@PathVariable String productId) {
        long begin = System.nanoTime();
        try {
            int available = stock.lockAvailableNowait(productId);
            long costMs = (System.nanoTime() - begin) / 1_000_000;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("locked", true);
            out.put("available", available);
            out.put("costMs", costMs);
            out.put("readMode", "FOR UPDATE");
            return out;
        } catch (org.springframework.dao.PessimisticLockingFailureException
                 | org.springframework.jdbc.UncategorizedSQLException e) {
            long costMs = (System.nanoTime() - begin) / 1_000_000;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("locked", false);
            out.put("reason", "行被未决的 Prepare 事务持有（3572/1205 类错误，NOWAIT 失败）");
            out.put("costMs", costMs);
            out.put("readMode", "FOR UPDATE NOWAIT");
            return out;
        }
    }

    /** 实验复位：把库存拨回种子值（available=20, frozen=0），并清理协议日志表。 */
    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestParam(defaultValue = "P-1001") String productId,
                                     @RequestParam(defaultValue = "20") int available) {
        stock.reset(productId, available, 0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reset", true);
        out.put("productId", productId);
        out.put("available", available);
        out.put("frozen", 0);
        return out;
    }
}
