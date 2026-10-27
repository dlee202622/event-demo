package com.example.demo.order.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface OrderJpaRepository extends JpaRepository<OrderEntity, String> {

    List<OrderEntity> findAllByOrderByCreatedAtDesc();
}
