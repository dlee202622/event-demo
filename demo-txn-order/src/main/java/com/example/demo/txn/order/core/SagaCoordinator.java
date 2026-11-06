package com.example.demo.txn.order.core;

import com.example.demo.txn.order.repository.OrderRepository;
import com.example.demo.txn.order.support.ParticipantClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Saga 协调者（编排式）：正向 B→C 依次执行，失败后按逆序补偿。
 *
 * <p>教学核心是「无隔离性窗口」：B 的扣减一旦提交立即对外可见。
 * compensateDelayMs 参数把「B 已扣、C 失败、补偿未执行」的窗口拉长到秒级，
 * 期间查询 B 的库存能看到减少——TCC 冻结看到的是 frozen+qty，
 * Saga 看到的是 available-qty，两种方案隔离性的差距一目了然。</p>
 *
 * <p>补偿消息的 msgId 带固定后缀：正向与补偿共用一个去重表时，
 * 必须是两个不同的幂等键，否则「先扣后退」会被当成重复消息吞掉。</p>
 */
@Service
public class SagaCoordinator {

    private final ParticipantClient client;
    private final OrderRepository orders;

    public SagaCoordinator(ParticipantClient client, OrderRepository orders) {
        this.client = client;
        this.orders = orders;
    }

    /**
     * @param qty               数量（B 扣库存）
     * @param points            积分（C 扣积分）
     * @param compensateDelayMs C 失败后、补偿 B 前的停顿（毫秒），拉宽无隔离性窗口；0 = 不停顿
     */
    public Map<String, Object> execute(int qty, int points, long compensateDelayMs) {
        String xid = "saga-" + UUID.randomUUID().toString().substring(0, 8);
        orders.insert(xid, "SAGA", "C-1001", "P-1001", qty, points, "SAGA_RUNNING", null);

        List<ParticipantClient.StepResult> steps = new ArrayList<>();
        // 正向链：msgId 末尾带 -f（forward），补偿用 -c（compensate）区分幂等键
        var deductB = client.post("inventory", "/api/saga/deduct/P-1001",
                Map.of("qty", qty, "msgId", xid + "-b-f"));
        steps.add(deductB);
        var deductC = client.post("loyalty", "/api/saga/deduct/C-1001",
                Map.of("points", points, "msgId", xid + "-c-f"));
        steps.add(deductC);

        if (deductC.ok()) {
            orders.updateStatus(xid, "CONFIRMED", "正向链全部成功（本地事务已提交，无回滚）");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("xid", xid);
            out.put("scheme", "SAGA");
            out.put("steps", steps);
            out.put("orderStatus", "CONFIRMED");
            return out;
        }

        // C 失败：先停顿（观察窗口），再逆序补偿 B。C 自己失败在本地事务里，
        // 积分分毫未动——不需要补偿，这正是 Saga「本地事务原子性」的含义
        if (compensateDelayMs > 0) {
            try {
                Thread.sleep(compensateDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (deductB.ok()) {
            var compensateB = client.post("inventory", "/api/saga/compensate/P-1001",
                    Map.of("qty", qty, "msgId", xid + "-b-c"));
            steps.add(compensateB);
        }
        orders.updateStatus(xid, "CANCELLED", "C 扣积分失败，已补偿 B 的扣减");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("xid", xid);
        out.put("scheme", "SAGA");
        out.put("steps", steps);
        out.put("orderStatus", "CANCELLED");
        out.put("note", "C 失败前的窗口内，B 的扣减对外可见（可用 compensateDelayMs 拉宽观察）");
        return out;
    }
}
