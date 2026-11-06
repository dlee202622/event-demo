package com.example.demo.txn.order.core;

import com.example.demo.txn.order.repository.OrderRepository;
import com.example.demo.txn.order.repository.OutboxRepository;
import com.example.demo.txn.order.support.ParticipantClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * 本地消息表（Transactional Outbox）方案：下单与消息同事务落库，
 * 后台重投器轮询发送 B/C，直到双方成功——最终一致。
 *
 * <p>原子性的来源只有一个：「订单行 + 消息行」写在同一个本地事务里
 * （{@link #placeOrder}）。之后无论 A 崩溃多少次，重启后重投器都能从
 * outbox_message 里找回未完成的通知；B/C 的 msgId 幂等保证重复投递
 * 只生效一次。这就是「至少一次投递 + 幂等消费 = 恰好一次效果」的组合。</p>
 *
 * <p>与 2PC/TCC 的定位差异：这不是强一致——下单返回时 B/C 可能还没执行，
 * 窗口内的总账暂时不平。适合积分、通知、统计等容忍秒级延迟的场景。</p>
 */
@Service
public class LocalMessageService {

    private static final Logger log = LoggerFactory.getLogger(LocalMessageService.class);

    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final ParticipantClient client;
    private final TransactionTemplate txTemplate;
    private final int maxRetries;

    public LocalMessageService(OrderRepository orders, OutboxRepository outbox,
                               ParticipantClient client, PlatformTransactionManager txManager,
                               org.springframework.core.env.Environment env) {
        this.orders = orders;
        this.outbox = outbox;
        this.client = client;
        // 编程式事务模板：placeOrder 要把两条 INSERT 包进同一个事务，
        // @Transactional 用在类内部自调用方法上不会生效，这里直接用模板最直白
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.maxRetries = env.getProperty("demo.txn.order.localmsg.max-retries", Integer.class, 10);
    }

    /**
     * 下单（最终一致版）：订单与消息同一本地事务提交后立即返回。
     * 此刻 B/C 还没收到任何调用——一致性由后台重投器逐步补齐。
     *
     * @return 立即返回的订单状态（INIT）与消息 ID，B/C 的完成情况查 /api/localmsg/messages
     */
    public Map<String, Object> placeOrder(int qty, int points) {
        String xid = "msg-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String msgId = xid + "-m";
        txTemplate.executeWithoutResult(tx -> {
            orders.insert(xid, "LOCALMSG", "C-1001", "P-1001", qty, points, "INIT", "等待消息投递");
            // 消息与订单同事务：这是全方案里唯一的强一致保证点
            outbox.insertPending(msgId, xid, "C-1001", "P-1001", qty, points);
        });
        return Map.of(
                "xid", xid,
                "scheme", "LOCALMSG",
                "orderStatus", "INIT",
                "msgId", msgId,
                "note", "订单已受理。B/C 的扣减由后台重投器完成，查看 /api/localmsg/messages");
    }

    /**
     * 重投器：每 relay-interval-ms 扫一轮到期消息，逐条投递 B/C。
     * fixedDelay 而非 fixedRate：上一轮没跑完不叠加下一轮，避免消息被并发投两次。
     */
    @Scheduled(fixedDelayString = "${demo.txn.order.localmsg.relay-interval-ms:500}")
    public void relay() {
        List<Map<String, Object>> due = outbox.duePending();
        for (Map<String, Object> msg : due) {
            String msgId = (String) msg.get("msg_id");
            long id = ((Number) msg.get("id")).longValue();
            int retryCount = ((Number) msg.get("retry_count")).intValue();
            // 两个参与者都以 msgId 幂等：先 B 后 C 逐个试，谁失败下一轮从谁继续，
            // 已成功的那个再收到同一条消息只会返回 DUPLICATE
            var toB = client.post("inventory", "/api/saga/deduct/" + msg.get("product_id"),
                    Map.of("qty", msg.get("qty"), "msgId", msgId + "-b"));
            var toC = client.post("loyalty", "/api/saga/deduct/" + msg.get("customer_id"),
                    Map.of("points", msg.get("points"), "msgId", msgId + "-c"));
            if (toB.ok() && toC.ok()) {
                outbox.markSent(id);
                orders.updateStatus((String) msg.get("xid"), "CONFIRMED", "消息投递完成，B/C 均已扣减");
                log.info("[localmsg] {} 投递成功", msgId);
            } else {
                String error = (toB.error() != null ? toB.error() : toC.error());
                outbox.markFailed(id, retryCount + 1, maxRetries, error);
                // 超过上限的 DEAD 消息同时把订单标记为失败，等待人工介入
                if (retryCount + 1 >= maxRetries) {
                    orders.updateStatus((String) msg.get("xid"), "DEAD", "消息投递失败超过上限：" + error);
                }
                log.warn("[localmsg] {} 投递失败（第 {} 次）：{}", msgId, retryCount + 1, error);
            }
        }
    }
}
