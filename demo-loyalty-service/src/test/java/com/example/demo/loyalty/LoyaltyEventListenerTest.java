package com.example.demo.loyalty;

import com.example.demo.loyalty.domain.PointsRepository;
import com.example.demo.loyalty.domain.event.Money;
import com.example.demo.loyalty.domain.event.OrderPaidEvent;
import com.example.demo.loyalty.event.LoyaltyEventListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 积分上下文行为测试：每消费 10 元得 1 分，向下取整。
 */
class LoyaltyEventListenerTest {

    private LoyaltyPointsService loyaltyPointsService;
    private LoyaltyEventListener listener;

    @BeforeEach
    void setUp() {
        loyaltyPointsService = new LoyaltyPointsService(new InMemoryPointsRepository());
        listener = new LoyaltyEventListener(loyaltyPointsService);
    }

    @Test
    @DisplayName("支付事件：按金额 10:1 累计积分（向下取整）")
    void earnsPointsProportionally() {
        listener.on(paid("ORD-1", "C-1", "798.00"));
        assertEquals(79, loyaltyPointsService.pointsOf("C-1"));

        listener.on(paid("ORD-2", "C-1", "100.00"));
        assertEquals(89, loyaltyPointsService.pointsOf("C-1"));
    }

    @Test
    @DisplayName("小额支付不足 10 元不产生积分")
    void noPointsBelowTen() {
        listener.on(paid("ORD-3", "C-2", "9.99"));
        assertEquals(0, loyaltyPointsService.pointsOf("C-2"));
    }

    @Test
    @DisplayName("积分按客户隔离")
    void pointsArePerCustomer() {
        listener.on(paid("ORD-4", "C-3", "50.00"));
        listener.on(paid("ORD-5", "C-4", "20.00"));

        assertEquals(5, loyaltyPointsService.pointsOf("C-3"));
        assertEquals(2, loyaltyPointsService.pointsOf("C-4"));
    }

    private static final class InMemoryPointsRepository implements PointsRepository {

        private final Map<String, Integer> points = new ConcurrentHashMap<>();

        @Override
        public int add(String customerId, int pointsToAdd) {
            return points.merge(customerId, pointsToAdd, Integer::sum);
        }

        @Override
        public int pointsOf(String customerId) {
            return points.getOrDefault(customerId, 0);
        }

        @Override
        public Map<String, Integer> findAll() {
            return new TreeMap<>(points);
        }
    }

    private static OrderPaidEvent paid(String orderId, String customerId, String amount) {
        return new OrderPaidEvent(
                "evt-" + orderId,
                orderId,
                customerId,
                new Money(new BigDecimal(amount), "CNY"),
                "PAY-" + orderId,
                Instant.now()
        );
    }
}
