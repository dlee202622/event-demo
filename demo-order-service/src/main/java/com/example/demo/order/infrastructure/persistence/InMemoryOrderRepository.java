package com.example.demo.order.infrastructure.persistence;

import com.example.demo.order.domain.model.Order;
import com.example.demo.order.domain.model.OrderId;
import com.example.demo.order.domain.repository.OrderRepository;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版订单仓储（演示用）。
 *
 * <p>换成 MySQL/JPA 时只需要：</p>
 * <ul>
 *   <li>在 {@code Order} 上加 @Entity/@Embeddable 映射，或另写 PO 做转换；</li>
 *   <li>这里改成 {@code JpaRepository<OrderPO, String>} + 装配器（Assembler）；</li>
 *   <li>领域层代码一行都不用改 —— 这就是端口-适配器的价值。</li>
 * </ul>
 */
@Repository
public class InMemoryOrderRepository implements OrderRepository {

    private final Map<String, Order> storage = new ConcurrentHashMap<>();

    @Override
    public void save(Order order) {
        storage.put(order.id().value(), order);
    }

    @Override
    public Optional<Order> findById(OrderId id) {
        return Optional.ofNullable(storage.get(id.value()));
    }

    @Override
    public List<Order> findAll() {
        return storage.values().stream()
                .sorted(Comparator.comparing(Order::createdAt).reversed())
                .toList();
    }

    /** 仅测试/演示用。 */
    public void clear() {
        storage.clear();
    }
}
