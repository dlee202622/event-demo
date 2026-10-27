package com.example.demo.order.infrastructure.listener;

import com.example.demo.order.domain.event.InventoryReservationFailedEvent;
import com.example.demo.order.application.OrderApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 订单上下文监听库存预占失败事件，执行补偿操作（取消订单）。
 *
 * <p>这是事件驱动架构中「Saga 模式」的简化实现：
 * 当某个步骤失败时，通过发布补偿事件来回滚之前的操作。</p>
 */
@Component
public class InventoryFailureCompensationHandler {

    private static final Logger log = LoggerFactory.getLogger(InventoryFailureCompensationHandler.class);

    private final OrderApplicationService orderApplicationService;

    public InventoryFailureCompensationHandler(OrderApplicationService orderApplicationService) {
        this.orderApplicationService = orderApplicationService;
    }

    /**
     * 监听库存预占失败事件，自动取消订单。
     */
    @EventListener
    public void on(InventoryReservationFailedEvent event) {
        log.warn("[补偿机制] 收到库存预占失败事件 orderId={} productId={} reason={}", 
                event.orderId(), event.productId(), event.reason());
        
        try {
            String cancelReason = String.format("库存预占失败：%s（商品 %s）", 
                    event.reason(), event.productId());
            orderApplicationService.cancelOrder(event.orderId(), cancelReason);
            log.info("[补偿机制] 订单已取消 orderId={} reason={}", event.orderId(), cancelReason);
        } catch (com.example.demo.eventdriven.domain.DomainException e) {
            // 订单已取消，忽略（幂等处理）
            log.info("[补偿机制] 订单已处于取消状态，跳过 orderId={} reason={}", 
                    event.orderId(), e.getMessage());
        } catch (Exception e) {
            log.error("[补偿机制] 取消订单失败 orderId={} error={}", event.orderId(), e.getMessage(), e);
            // 生产环境应该将失败事件持久化，由人工或定时任务重试
        }
    }
}
