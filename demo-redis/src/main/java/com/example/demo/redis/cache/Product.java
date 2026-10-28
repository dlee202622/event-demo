package com.example.demo.redis.cache;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品：延迟双删 demo 里唯一的业务实体。
 */
public record Product(Long id, String name, BigDecimal price, int version, LocalDateTime updatedAt) {
}
