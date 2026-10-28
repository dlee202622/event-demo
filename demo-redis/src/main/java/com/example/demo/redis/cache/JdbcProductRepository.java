package com.example.demo.redis.cache;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 基于 JdbcTemplate 的商品仓储，复用 docker-compose 里的 MySQL。
 *
 * <p>缓存演示只需要一张表和三条 SQL，用 JdbcTemplate 比 JPA 更直观：
 * 每条 SQL 的结果都能和延迟双删的时序对应上。表结构见 {@code schema.sql}，
 * 初始数据见 {@code data.sql}。</p>
 */
@Repository
public class JdbcProductRepository implements ProductRepository {

    /** 显式映射列，避免依赖数据库列顺序；updated_at 由 MySQL 自动维护。 */
    private static final RowMapper<Product> ROW_MAPPER = (rs, rowNum) -> new Product(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getBigDecimal("price"),
            rs.getInt("version"),
            rs.getTimestamp("updated_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    /** 注入 Spring 自动配置的 JdbcTemplate（数据源来自 application.yaml）。 */
    public JdbcProductRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按主键查询；返回 Optional 让上层可以区分「不存在」和「查询失败」。 */
    @Override
    public Optional<Product> findById(long id) {
        List<Product> products = jdbc.query(
                "SELECT id, name, price, version, updated_at FROM product WHERE id = ?",
                ROW_MAPPER, id);
        return products.stream().findFirst();
    }

    /**
     * 更新名称和价格，并把 version +1（便于观察写入确实发生）。
     *
     * <p>影响行数为 0 说明 id 不存在，直接抛参数错误，避免上层以为更新成功。</p>
     */
    @Override
    public void update(long id, String name, BigDecimal price) {
        int rows = jdbc.update(
                "UPDATE product SET name = ?, price = ?, version = version + 1 WHERE id = ?",
                name, price, id);
        if (rows == 0) {
            throw new IllegalArgumentException("商品不存在: " + id);
        }
    }

    /**
     * 幂等写入：存在则覆盖，不存在则插入。
     *
     * <p>竞态实验每轮都调用它把数据重置成旧值；MySQL 的
     * {@code ON DUPLICATE KEY UPDATE} 保证重复执行不会报主键冲突。</p>
     */
    @Override
    public void upsert(Product product) {
        jdbc.update("""
                        INSERT INTO product (id, name, price, version)
                        VALUES (?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE name = VALUES(name), price = VALUES(price), version = VALUES(version)
                        """,
                product.id(), product.name(), product.price(), product.version());
    }
}
