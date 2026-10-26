package com.example.demo.order.application.query;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 订单查询服务（CQRS 的读侧）。
 *
 * <p>只读读模型，绝不加载聚合、不发布事件 —— 这是 CQRS 的核心约束：
 * 查询走轻量读模型，避免为了展示一个列表而重建整个聚合。</p>
 */
@Service
public class OrderQueryService {

    private final OrderViewStore viewStore;

    public OrderQueryService(OrderViewStore viewStore) {
        this.viewStore = viewStore;
    }

    public List<OrderView> findAll() {
        return viewStore.findAll().stream()
                .sorted(Comparator.comparing(OrderView::createdAt).reversed())
                .toList();
    }

    public OrderView findByOrderId(String orderId) {
        return viewStore.findById(orderId)
                .orElseThrow(() -> new OrderViewNotFoundException(orderId));
    }

    /** 读模型还没追上（最终一致性的正常现象）时使用。 */
    public static class OrderViewNotFoundException extends RuntimeException {
        public OrderViewNotFoundException(String orderId) {
            super("订单不存在或读模型尚未同步: " + orderId);
        }
    }
}
