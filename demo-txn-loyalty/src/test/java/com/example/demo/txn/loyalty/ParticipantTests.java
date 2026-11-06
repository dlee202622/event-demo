package com.example.demo.txn.loyalty;

import com.example.demo.txn.loyalty.core.SagaParticipant;
import com.example.demo.txn.loyalty.core.SeataBranchService;
import com.example.demo.txn.loyalty.core.TccParticipant;
import com.example.demo.txn.loyalty.core.TwoPcParticipant;
import com.example.demo.txn.loyalty.repository.PointsRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 积分侧参与者状态机测试：与 B 的 ParticipantTests 一一对应，
 * 额外断言 ledger 流水（C 特有）与 Seata 分支（无协议代码）的可用性。
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
        // 单测不依赖 Seata TC：关闭客户端，AT 分支退化为普通本地事务
        registry.add("seata.enabled", () -> "false");
    }

    @Autowired TwoPcParticipant twoPc;
    @Autowired TccParticipant tcc;
    @Autowired SagaParticipant saga;
    @Autowired SeataBranchService seata;
    @Autowired PointsRepository points;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetState() {
        points.reset("C-1001", 100);
        jdbc.update("DELETE FROM txn_prepare_log");
        jdbc.update("DELETE FROM tcc_log");
        jdbc.update("DELETE FROM processed_msg");
        jdbc.update("DELETE FROM points_ledger");
        tcc.setGuard(true);
    }

    @Test
    void twoPcPrepareHoldsUntilCommit() throws Exception {
        assertEquals("YES", twoPc.prepare("t-1", "C-1001", 10).get("result"));
        // prepare 事务句柄绑定当前线程，跨会话读必须换线程（见 B 侧同名测试说明）
        Integer other = java.util.concurrent.Executors.newSingleThreadExecutor()
                .submit(() -> balance()).get();
        assertEquals(100, other); // 未提交不可见
        assertEquals("COMMITTED", twoPc.commit("t-1").get("result"));
        assertEquals(90, balance());
    }

    @Test
    void twoPcVotesNoWhenInsufficient() {
        assertEquals("NO", twoPc.prepare("t-2", "C-1001", 999).get("result"));
        assertEquals(100, balance());
        assertEquals(0, twoPc.status().get("pendingTxCount"));
    }

    @Test
    void tccFreezeConfirmAndIdempotency() {
        assertEquals("FROZEN", tcc.tryFreeze("t-3", "C-1001", 10).get("result"));
        assertEquals(90, balance());
        assertEquals(10, frozen());
        assertEquals("CONFIRMED", tcc.confirm("t-3").get("result"));
        assertEquals(90, balance());
        assertEquals(0, frozen());
        assertEquals("CONFIRMED_DUPLICATE", tcc.confirm("t-3").get("result"));
        assertEquals(90, balance());
    }

    @Test
    void tccEmptyRollbackAndHangingGuard() {
        assertEquals("EMPTY_ROLLBACK", tcc.cancel("t-4").get("result"));
        assertEquals("REJECTED_AFTER_CANCEL", tcc.tryFreeze("t-4", "C-1001", 10).get("result"));
        assertEquals(100, balance());

        // 防护关闭时悬挂成立：孤儿冻结 10 分无人处理
        tcc.setGuard(false);
        assertEquals("EMPTY_ROLLBACK_UNPROTECTED", tcc.cancel("t-5").get("result"));
        assertEquals("FROZEN", tcc.tryFreeze("t-5", "C-1001", 10).get("result"));
        assertEquals(10, frozen());
        tcc.setGuard(true);
        tcc.cancel("t-5");
    }

    @Test
    void sagaDeductCompensateAndLedger() {
        assertEquals("DEDUCTED", saga.deduct("m-1", "C-1001", 10).get("result"));
        assertEquals("DUPLICATE", saga.deduct("m-1", "C-1001", 10).get("result"));
        assertEquals(90, balance());
        assertEquals("COMPENSATED", saga.compensate("m-1-c", "C-1001", 10).get("result"));
        assertEquals(100, balance());
        // 流水：一次 DEDUCT + 一次 COMPENSATE，重复投递不计入
        Integer deductRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM points_ledger WHERE type = 'DEDUCT'", Integer.class);
        Integer compRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM points_ledger WHERE type = 'COMPENSATE'", Integer.class);
        assertEquals(1, deductRows);
        assertEquals(1, compRows);
    }

    @Test
    void seataBranchDeductsPlainly() {
        // 未接入 Seata 时这是普通本地事务；接入后同一方法被数据源代理接管——
        // 这正是 AT 模式的卖点：参与者代码在两种形态下完全一致
        var out = seata.deduct("C-1001", 10);
        assertEquals("DEDUCTED", out.get("result"));
        assertEquals(90, balance());
        // 全局事务之外运行时 undo_log 不会被写入
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM undo_log", Integer.class));
    }

    private int balance() {
        return ((Number) points.findPlain("C-1001").orElseThrow().get("balance")).intValue();
    }

    private int frozen() {
        return ((Number) points.findPlain("C-1001").orElseThrow().get("frozen")).intValue();
    }
}
