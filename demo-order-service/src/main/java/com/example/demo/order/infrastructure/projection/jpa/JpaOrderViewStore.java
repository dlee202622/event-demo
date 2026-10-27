package com.example.demo.order.infrastructure.projection.jpa;

import com.example.demo.order.application.query.OrderView;
import com.example.demo.order.application.query.OrderViewStore;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 订单读模型的 JPA 存储实现。 */
@Repository
public class JpaOrderViewStore implements OrderViewStore {

    private final OrderViewJpaRepository jpaRepository;

    public JpaOrderViewStore(OrderViewJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void save(OrderView view) {
        jpaRepository.save(OrderViewEntity.from(view));
    }

    @Override
    public Optional<OrderView> findById(String orderId) {
        return jpaRepository.findById(orderId).map(OrderViewEntity::toView);
    }

    @Override
    public List<OrderView> findAll() {
        return jpaRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(OrderViewEntity::toView)
                .toList();
    }

    @Override
    public void clear() {
        jpaRepository.deleteAllInBatch();
    }
}
