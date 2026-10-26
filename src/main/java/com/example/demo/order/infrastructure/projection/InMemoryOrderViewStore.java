package com.example.demo.order.infrastructure.projection;

import com.example.demo.order.application.query.OrderView;
import com.example.demo.order.application.query.OrderViewStore;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 内存版读模型存储（生产可替换为 ES / 只读库 / Redis）。 */
@Repository
public class InMemoryOrderViewStore implements OrderViewStore {

    private final Map<String, OrderView> views = new ConcurrentHashMap<>();

    @Override
    public void save(OrderView view) {
        views.put(view.orderId(), view);
    }

    @Override
    public Optional<OrderView> findById(String orderId) {
        return Optional.ofNullable(views.get(orderId));
    }

    @Override
    public List<OrderView> findAll() {
        return views.values().stream()
                .sorted(Comparator.comparing(OrderView::createdAt).reversed())
                .toList();
    }

    @Override
    public void clear() {
        views.clear();
    }
}
