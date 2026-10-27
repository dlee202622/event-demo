package com.example.demo.order.application;

import com.example.demo.order.application.command.PlaceOrderCommand;
import com.example.demo.order.domain.model.Money;
import com.example.demo.order.domain.model.Order;
import com.example.demo.order.domain.model.OrderId;
import com.example.demo.order.domain.model.OrderItem;
import com.example.demo.order.domain.repository.OrderRepository;
import com.example.demo.eventdriven.domain.DomainEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 订单应用服务：编排用例，不包含业务规则。
 *
 * <p>固定套路（Application Service 的三段式）：</p>
 * <pre>
 *   1) 加载聚合（或通过工厂创建）
 *   2) 调用聚合的业务方法（业务规则都在聚合里）
 *   3) 保存聚合 → 发布聚合登记的领域事件
 * </pre>
 *
 * <p>「先保存、后发布」非常重要：如果先发事件再保存，消费者可能查到还没落库的数据。
 * 生产环境还会用本地事务 + Outbox 保证这两步的原子性（见 OutboxRelay 注释）。</p>
 */
@Service
public class OrderApplicationService {

    private static final Logger log = LoggerFactory.getLogger(OrderApplicationService.class);

    private final OrderRepository orderRepository;
    private final DomainEventPublisher domainEventPublisher;

    public OrderApplicationService(OrderRepository orderRepository,
                                   DomainEventPublisher domainEventPublisher) {
        this.orderRepository = orderRepository;
        this.domainEventPublisher = domainEventPublisher;
    }

    /** 用例：下单。 */
    @Transactional
    public OrderId placeOrder(PlaceOrderCommand command) {
        List<OrderItem> items = command.lines().stream()
                .map(line -> new OrderItem(
                        line.productId(),
                        line.productName(),
                        new Money(line.unitPrice(), Money.CNY),
                        line.quantity()))
                .toList();

        Order order = Order.place(orderRepository.nextId(), command.customerId(), items);

        orderRepository.save(order);                 // 持久化聚合
        publishEvents(order);                        // 再发布事件

        log.info("[应用服务] 下单成功 orderId={} 金额={}", order.id().value(), order.totalAmount().display());
        return order.id();
    }

    /** 用例：确认订单。 */
    @Transactional
    public void confirmOrder(String orderId) {
        Order order = load(orderId);
        order.confirm();
        orderRepository.save(order);
        publishEvents(order);
    }

    /** 用例：支付订单。 */
    @Transactional
    public void payOrder(String orderId, String paymentId) {
        Order order = load(orderId);
        order.pay(paymentId);
        orderRepository.save(order);
        publishEvents(order);
    }

    /** 用例：取消订单。 */
    @Transactional
    public void cancelOrder(String orderId, String reason) {
        Order order = load(orderId);
        order.cancel(reason);
        orderRepository.save(order);
        publishEvents(order);
    }

    private Order load(String orderId) {
        return orderRepository.getById(OrderId.of(orderId));
    }

    /** 统一出口：拉取并发布事件（拉取即清空，天然防重复投递）。 */
    private void publishEvents(Order order) {
        domainEventPublisher.publish(order.pullDomainEvents());
    }
}
