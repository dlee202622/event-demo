package com.example.demo.order.infrastructure.persistence.jpa;

import com.example.demo.order.domain.model.OrderStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

interface OrderJpaRepository extends JpaRepository<OrderEntity, String> {

    List<OrderEntity> findAllByOrderByCreatedAtDesc();

    List<OrderEntity> findByStatusInAndCreatedAtBeforeOrderByCreatedAtAsc(
            List<OrderStatus> statuses, Instant cutoff, Limit limit);
}
