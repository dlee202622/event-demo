package com.example.demo.inventory.infrastructure.persistence;

import com.example.demo.inventory.domain.Stock;
import com.example.demo.inventory.domain.StockRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** 库存仓储的 JPA 实现：写路径用悲观行锁串行化同一商品。 */
@Repository
public class JpaStockRepository implements StockRepository {

    private final StockJpaRepository jpaRepository;

    public JpaStockRepository(StockJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Stock> findById(String productId) {
        return jpaRepository.findById(productId).map(StockEntity::toDomain);
    }

    @Override
    @Transactional
    public Optional<Stock> findByIdForUpdate(String productId) {
        return jpaRepository.findForUpdate(productId).map(StockEntity::toDomain);
    }

    @Override
    @Transactional
    public void save(Stock stock) {
        StockEntity entity = jpaRepository.findById(stock.productId()).orElseThrow(
                () -> new IllegalArgumentException("库存记录不存在: " + stock.productId()));
        entity.apply(stock);
        jpaRepository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Stock> findAll() {
        return jpaRepository.findAll().stream()
                .map(StockEntity::toDomain)
                .toList();
    }
}
