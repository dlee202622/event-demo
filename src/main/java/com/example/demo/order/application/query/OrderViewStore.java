package com.example.demo.order.application.query;

import java.util.List;
import java.util.Optional;

/**
 * 读模型存储端口。
 *
 * <p>写侧（投影器）通过 {@link #save(OrderView)} 更新，读侧（查询服务）通过查询方法读取。
 * 生产中这里通常换成 ES / 只读库 / Redis，读写两侧都不需要改动。</p>
 */
public interface OrderViewStore {

    void save(OrderView view);

    Optional<OrderView> findById(String orderId);

    List<OrderView> findAll();

    void clear();
}
