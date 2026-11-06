package com.example.demo.txn.inventory;

import com.example.demo.txn.inventory.core.SagaParticipant;
import com.example.demo.txn.inventory.core.TccParticipant;
import com.example.demo.txn.inventory.core.TwoPcParticipant;
import com.example.demo.txn.inventory.repository.StockRepository;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参与者状态机测试：Testcontainers 起独立 MySQL，不碰本机常驻库。
 * 断言库存数值而不是只断言「成功」——分布式事务的教学本质就是数值变化。
 */
@SpringBootTest
@Testcontainers
class ParticipantTests {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        // 单测不依赖 Seata TC：关闭客户端，/api/seata 分支退化为普通本地事务
        registry.add("seata.enabled", () -> "false");
    }

    @Autowired TwoPcParticipant twoPc;
    @Autowired TccParticipant tcc;
    @Autowired SagaParticipant saga;
    @Autowired StockRepository stock;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        // 每个用例从确定状态出发：库存 20/0、协议日志清空
        stock.reset("P-1001", 20, 0);
        jdbc.update("DELETE FROM txn_prepare_log");
        jdbc.update("DELETE FROM tcc_log");
        jdbc.update("DELETE FROM processed_msg");
        tcc.setGuard(true);
    }

    // ---------- 2PC ----------

    @Test
    void twoPcPrepareHoldsLockUntilCommit() throws Exception {
        var yes = twoPc.prepare("t-1", "P-1001", 1);
        assertEquals("YES", yes.get("result"));
        // 跨会话视角：prepare 的事务句柄绑定在当前线程，同线程的 JDBC 读会复用
        // 同一连接、看到未提交数据——必须换线程才是真实的「另一个会话」
        assertEquals(20, availableFromOtherSession());
        assertEquals(1, twoPc.status().get("pendingTxCount"));

        var committed = twoPc.commit("t-1");
        assertEquals("COMMITTED", committed.get("result"));
        // 全局提交后才真正生效
        assertEquals(19, available());
        assertEquals(0, twoPc.status().get("pendingTxCount"));
    }

    @Test
    void twoPcRollbackRestoresStock() {
        twoPc.prepare("t-2", "P-1001", 2);
        twoPc.rollback("t-2");
        assertEquals(20, available());
    }

    @Test
    void twoPcVotesNoWhenInsufficient() {
        var no = twoPc.prepare("t-3", "P-1001", 100);
        assertEquals("NO", no.get("result"));
        // 投 NO 的分支必须自回滚：库存分毫未动，也没有未决事务
        assertEquals(20, available());
        assertEquals(0, twoPc.status().get("pendingTxCount"));
    }

    // ---------- TCC ----------

    @Test
    void tccTryFreezesThenConfirmDeducts() {
        assertEquals("FROZEN", tcc.tryFreeze("t-4", "P-1001", 2).get("result"));
        // 冻结的可见形态：available 减少、frozen 增加——与 2PC 的完全不可见不同
        assertEquals(18, available());
        assertEquals(2, frozen());

        assertEquals("CONFIRMED", tcc.confirm("t-4").get("result"));
        assertEquals(18, available());
        assertEquals(0, frozen());
        // 幂等：重复 Confirm 被吸收，额度不再变化
        assertEquals("CONFIRMED_DUPLICATE", tcc.confirm("t-4").get("result"));
        assertEquals(18, available());
    }

    @Test
    void tccCancelUnfreezes() {
        tcc.tryFreeze("t-5", "P-1001", 3);
        assertEquals("CANCELLED", tcc.cancel("t-5").get("result"));
        assertEquals(20, available());
        assertEquals(0, frozen());
    }

    @Test
    void emptyRollbackBlocksLateTry() {
        // 空回滚：Try 未到，Cancel 先来——占位而不是报错
        assertEquals("EMPTY_ROLLBACK", tcc.cancel("t-6").get("result"));
        // 悬挂防护：迟到的 Try 被拒绝，不会产生无人处理的孤儿冻结
        assertEquals("REJECTED_AFTER_CANCEL", tcc.tryFreeze("t-6", "P-1001", 1).get("result"));
        assertEquals(20, available());
        assertEquals(0, frozen());
    }

    @Test
    void guardOffReproducesHangingFreeze() {
        // 关闭防护 = 教程的「防护前」：空回滚不占位，迟到的 Try 冻结成功
        tcc.setGuard(false);
        assertEquals("EMPTY_ROLLBACK_UNPROTECTED", tcc.cancel("t-7").get("result"));
        assertEquals("FROZEN", tcc.tryFreeze("t-7", "P-1001", 1).get("result"));
        // 孤儿冻结成立：frozen=1 且永远没人 Confirm/Cancel
        assertEquals(1, frozen());
        // 收尾清理，避免影响后续用例
        tcc.setGuard(true);
        tcc.cancel("t-7");
    }

    // ---------- Saga ----------

    @Test
    void sagaDeductIsIdempotentByMsgId() {
        assertEquals("DEDUCTED", saga.deduct("m-1", "P-1001", 2).get("result"));
        assertEquals(18, available());
        // 同一 msgId 重投：不再扣减
        assertEquals("DUPLICATE", saga.deduct("m-1", "P-1001", 2).get("result"));
        assertEquals(18, available());
    }

    @Test
    void sagaCompensateRestoresWithDedup() {
        saga.deduct("m-2", "P-1001", 1);
        assertEquals("COMPENSATED", saga.compensate("m-2-c", "P-1001", 1).get("result"));
        assertEquals(20, available());
        // 补偿重复投递：不虚增库存
        assertEquals("DUPLICATE", saga.compensate("m-2-c", "P-1001", 1).get("result"));
        assertEquals(20, available());
    }

    @Test
    void sagaDeductFailsCleanlyWhenInsufficient() {
        // 本地事务失败：抛业务异常、什么都没发生，也不留任何协议痕迹（无日志表可清理）
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> saga.deduct("m-3", "P-1001", 999));
        assertEquals(20, available());
        assertTrue(jdbc.queryForList("SELECT * FROM processed_msg").isEmpty());
    }

    private int available() {
        return ((Number) stock.findPlain("P-1001").orElseThrow().get("available")).intValue();
    }

    /**
     * 另一个数据库会话视角的读数：在独立线程执行（新连接、autocommit），
     * 只能看到已提交数据——2PC「未提交不可见」的断言靠它成立。
     */
    private int availableFromOtherSession() throws Exception {
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            return executor.submit(() ->
                    ((Number) stock.findPlain("P-1001").orElseThrow().get("available")).intValue()).get();
        } finally {
            executor.shutdown();
        }
    }

    private int frozen() {
        return ((Number) stock.findPlain("P-1001").orElseThrow().get("frozen")).intValue();
    }
}
