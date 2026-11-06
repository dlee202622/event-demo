package com.example.demo.txn.order.core;

import com.example.demo.txn.order.repository.OrderRepository;
import com.example.demo.txn.order.support.ParticipantClient;
import org.apache.seata.core.context.RootContext;
import org.apache.seata.spring.annotation.GlobalTransactional;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Seata AT 发起方：一个注解替代全部协议代码，与前四个方案形成「人工 vs 框架」对照。
 *
 * <p>方法内的三个动作——订单 INSERT（本库分支）、B 扣库存、C 扣积分——
 * 全部被 Seata 托管：分支提交前数据源代理写 undo_log 前后镜像；
 * 本方法抛出任何异常，TM 向 TC 发起全局回滚，各 RM 按镜像自动反向补偿。
 * 前面手写方案里的 Prepare 日志、tcc_log、补偿调用在这里一行都不需要。</p>
 *
 * <p>适用的原因：三个动作都是关系库写。不适用的原因：若某个动作是发消息/
 * 调第三方（无数据库镜像），AT 就管不到了——教学上这是 AT 与 TCC/Saga 的分界线。</p>
 *
 * <p>坑：rollbackFor 必须显式包含 Exception——@GlobalTransactional 默认
 * 只按 RuntimeException 与 Error 回滚，受检异常会被吞成「正常提交」。</p>
 */
@Service
public class SeataOrderService {

    private final ParticipantClient client;
    private final OrderRepository orders;

    public SeataOrderService(ParticipantClient client, OrderRepository orders) {
        this.client = client;
        this.orders = orders;
    }

    /**
     * 发起 AT 全局事务：订单 + 扣库存 + 扣积分。
     * C 失败时（故障注入）直接抛出 → 全局回滚 → B 的扣减被 undo_log 反向补偿，
     * 订单 INSERT 也被回滚（orders 表里不会留下垃圾行——对比 Saga 会留 CANCELLED 行）。
     */
    @GlobalTransactional(name = "seata-order-demo", rollbackFor = Exception.class)
    public Map<String, Object> place(int qty, int points) {
        // XID 由 TC 下发并绑定在当前线程；把它写进响应，教程可拿它去 TC 控制台核对
        String xid = RootContext.getXID();
        List<ParticipantClient.StepResult> steps = new ArrayList<>();

        // 分支 1：本库订单。走被代理的数据源，提交前写 undo_log
        orders.insert(xid, "SEATA", "C-1001", "P-1001", qty, points, "CONFIRMING", "AT 全局事务进行中");

        // 分支 2/3：HTTP 到 B/C，XID 由 ParticipantClient 的拦截逻辑放进 TX_XID 头
        var deductB = client.post("inventory", "/api/seata/deduct/P-1001", Map.of("qty", qty));
        steps.add(deductB);
        if (!deductB.ok()) {
            // 抛出即回滚：B 已提交的分支由 TC 通知 RM 按 undo_log 反向补偿。
            // 注意这里没有任何「补偿 B」的代码——AT 的核心卖点
            throw new IllegalStateException("B 扣库存失败，触发全局回滚：" + deductB.error());
        }
        var deductC = client.post("loyalty", "/api/seata/deduct/C-1001", Map.of("points", points));
        steps.add(deductC);
        if (!deductC.ok()) {
            throw new IllegalStateException("C 扣积分失败，触发全局回滚：" + deductC.error());
        }

        orders.updateStatus(xid, "CONFIRMED", "AT 全局提交");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("xid", xid);
        out.put("scheme", "SEATA");
        out.put("steps", steps);
        out.put("orderStatus", "CONFIRMED");
        out.put("note", "三个分支全部由 Seata 托管：无 Prepare 日志、无 tcc_log、无补偿代码");
        return out;
    }
}
