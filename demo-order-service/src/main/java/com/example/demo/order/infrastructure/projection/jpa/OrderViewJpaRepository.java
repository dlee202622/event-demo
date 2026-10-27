package com.example.demo.order.infrastructure.projection.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface OrderViewJpaRepository extends JpaRepository<OrderViewEntity, String> {

    List<OrderViewEntity> findAllByOrderByCreatedAtDesc();
}
