package com.example.demo.loyalty.domain;

import java.util.Map;

/** 积分仓储端口。 */
public interface PointsRepository {

    /** 累加积分并返回最新累计值（单条 SQL 原子完成）。 */
    int add(String customerId, int points);

    int pointsOf(String customerId);

    Map<String, Integer> findAll();
}
