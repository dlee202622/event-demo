package com.example.demo.txn.loyalty.core;

import com.example.demo.txn.loyalty.support.FaultRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Seata AT 分支（积分侧）：唯一一个「不写协议代码」的方案。
 *
 * <p>对比前面三个类：这里只有一个普通的 {@code @Transactional} 方法——
 * 框架的数据源代理会在分支提交前自动解析 SQL 前后镜像写入 undo_log，
 * 全局回滚时按镜像反向补偿。这正是 AT 模式「对业务无侵入」的卖点，
 * 也是它和手写 TCC/Saga 最直观的差距：参与者这边几乎零成本。</p>
 *
 * <p>适用场景：关系库 CRUD、并发冲突不激烈的全局事务（回滚靠反向 SQL，
 * 高竞争下可能失败重试）。不适合：涉及外部系统调用的长事务（没有镜像可回放）。</p>
 *
 * <p>坑：AT 的隔离性来自全局锁（TC 侧），本方法里的行被代理自动注册；
 * 若有人绕过代理直连同一个库改数据，全局锁管不到它，隔离性就破了。</p>
 */
@Service
public class SeataBranchService {

    private final JdbcTemplate jdbc;
    private final FaultRegistry fault;

    public SeataBranchService(JdbcTemplate jdbc, FaultRegistry fault) {
        this.jdbc = jdbc;
        this.fault = fault;
    }

    /**
     * AT 分支业务：直接扣积分，无需冻结、无需补偿代码。
     * 故障注入照常生效——抛出异常时 Seata 的分支会被标记回滚，
     * undo_log 里的前后镜像被用来生成反向补偿 SQL。
     */
    @Transactional
    public Map<String, Object> deduct(String customerId, int pointsToDeduct) {
        fault.check("loyalty seata deduct");
        // FOR UPDATE 依然要写：全局锁防的是「多个全局事务互相踩」，
        // 本地行锁防的是「同一个库里的本地事务踩进来」，两层保护缺一不可
        Integer balance = jdbc.queryForObject(
                "SELECT balance FROM points_account WHERE customer_id = ? FOR UPDATE", Integer.class, customerId);
        if (balance == null || balance < pointsToDeduct) {
            throw new IllegalStateException("积分不足：balance=" + balance + " 需要=" + pointsToDeduct);
        }
        // 这条 UPDATE 会被数据源代理拦下来：解析前镜像（balance 旧值）写 undo_log，
        // 提交前再补后镜像。业务代码对此完全无感
        jdbc.update("UPDATE points_account SET balance = balance - (?) WHERE customer_id = ?",
                pointsToDeduct, customerId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branch", "loyalty");
        out.put("result", "DEDUCTED");
        out.put("customerId", customerId);
        out.put("points", pointsToDeduct);
        return out;
    }

    /**
     * undo_log 观测：全局事务进行中 count>0（镜像待命），
     * 全局提交后 TC 异步清理，最终归零——这是判断「分支真的被 Seata 管理」的依据。
     */
    public Map<String, Object> status() {
        return Map.of(
                "branch", "loyalty",
                "undoLogCount", jdbc.queryForObject("SELECT COUNT(*) FROM undo_log", Integer.class));
    }
}
