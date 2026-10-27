package com.example.demo.order.application;

import com.example.demo.eventdriven.domain.DomainException;
import com.example.demo.order.domain.model.Order;
import com.example.demo.order.domain.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 超时未支付订单的清理用例。
 *
 * <p>由调度任务定时触发；逐单调用 {@link OrderApplicationService#cancelOrder}，
 * 每单一个独立事务，单笔失败不影响本批其余订单。</p>
 */
@Service
public class OrderExpiryService {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryService.class);

    private final OrderRepository orderRepository;
    private final OrderApplicationService orderApplicationService;

    public OrderExpiryService(OrderRepository orderRepository,
                              OrderApplicationService orderApplicationService) {
        this.orderRepository = orderRepository;
        this.orderApplicationService = orderApplicationService;
    }

    /**
     * 取消创建时间早于 now - unpaidTimeout 的未支付订单。
     *
     * @return 本批实际取消的订单数
     */
    public int cancelExpired(Duration unpaidTimeout, int limit) {
        Instant cutoff = Instant.now().minus(unpaidTimeout);
        List<Order> candidates = orderRepository.findUnpaidCreatedBefore(cutoff, limit);

        int cancelled = 0;
        for (Order order : candidates) {
            try {
                orderApplicationService.cancelOrder(order.id().value(), "超时未支付");
                cancelled++;
            } catch (DomainException e) {
                // 并发下可能刚被支付或取消，跳过即可
                log.info("[超时取消] 跳过订单 {}：{}", order.id().value(), e.getMessage());
            }
        }

        if (cancelled > 0) {
            log.info("[超时取消] cutoff={} 候选 {} 笔，实际取消 {} 笔",
                    cutoff, candidates.size(), cancelled);
        }
        return cancelled;
    }
}
