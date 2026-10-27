package com.example.demo.order.infrastructure.persistence.jpa;

import com.example.demo.order.domain.model.Order;
import com.example.demo.order.domain.model.OrderId;
import com.example.demo.order.domain.model.OrderStatus;
import com.example.demo.order.domain.repository.OrderRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 订单聚合的 JPA 仓储实现。 */
@Repository
public class JpaOrderRepository implements OrderRepository {

    private final OrderJpaRepository jpaRepository;

    public JpaOrderRepository(OrderJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public void save(Order order) {
        OrderEntity entity = jpaRepository.findById(order.id().value()).orElse(null);
        if (entity == null) {
            entity = OrderEntity.from(order);
        } else {
            entity.applyState(order);
        }
        jpaRepository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Order> findById(OrderId id) {
        return jpaRepository.findById(id.value()).map(OrderEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> findAll() {
        return jpaRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(OrderEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Order> findUnpaidCreatedBefore(Instant cutoff, int limit) {
        return jpaRepository.findByStatusInAndCreatedAtBeforeOrderByCreatedAtAsc(
                        List.of(OrderStatus.CREATED, OrderStatus.CONFIRMED), cutoff, Limit.of(limit))
                .stream()
                .map(OrderEntity::toDomain)
                .toList();
    }
}
