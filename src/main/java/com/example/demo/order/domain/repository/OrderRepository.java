package com.example.demo.order.domain.repository;

import com.example.demo.order.domain.model.Order;
import com.example.demo.order.domain.model.OrderId;

import java.util.List;
import java.util.Optional;

/**
 * 订单仓储接口（领域层定义，基础设施层实现）。
 *
 * <p>注意它看起来像「集合」而不是 DAO：领域层只说 save / findById，
 * 不关心是 MySQL、MongoDB 还是内存 Map。这正是端口-适配器（六边形架构）的用法。</p>
 */
public interface OrderRepository {

    /** 保存聚合（新增或更新）。 */
    void save(Order order);

    Optional<Order> findById(OrderId id);

    /** 找不到时直接抛异常，避免业务代码到处写 orElseThrow。 */
    default Order getById(OrderId id) {
        return findById(id).orElseThrow(() -> new OrderNotFoundException(id.value()));
    }

    List<Order> findAll();

    /** 分配下一个订单号；具体实现可换成号段/雪花算法。 */
    default OrderId nextId() {
        return OrderId.generate();
    }
}
