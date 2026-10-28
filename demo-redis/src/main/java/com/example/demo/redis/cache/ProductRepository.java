package com.example.demo.redis.cache;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 商品存储接口：生产环境接 MySQL，测试里可以换成内存实现。
 */
public interface ProductRepository {

    Optional<Product> findById(long id);

    void update(long id, String name, BigDecimal price);

    void upsert(Product product);
}
