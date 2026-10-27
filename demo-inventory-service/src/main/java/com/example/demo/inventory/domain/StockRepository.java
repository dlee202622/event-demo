package com.example.demo.inventory.domain;

import java.util.List;
import java.util.Optional;

/** 库存仓储端口。 */
public interface StockRepository {

    Optional<Stock> findById(String productId);

    /** 加行锁读取，用于预占/释放。 */
    Optional<Stock> findByIdForUpdate(String productId);

    void save(Stock stock);

    List<Stock> findAll();
}
