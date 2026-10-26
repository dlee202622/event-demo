package com.example.demo.shared.domain;

import java.time.Instant;

/**
 * 领域事件标记接口。
 *
 * <p>DDD 中「领域事件」表示聚合内部状态发生的一次有意义的变化，使用【过去时】命名，例如
 * OrderCreatedEvent、OrderPaidEvent。它是限界上下文内部的事实陈述，本身不可变。</p>
 *
 * <p>关键约定：</p>
 * <ul>
 *   <li>事件必须携带 eventId，用于消费端幂等去重（至少一次投递语义下的必备字段）。</li>
 *   <li>事件必须携带 aggregateId，用于追踪它来自哪个聚合。</li>
 *   <li>事件只描述「发生了什么」，不描述「要别人做什么」——避免变成变相的 RPC 调用。</li>
 * </ul>
 */
public interface DomainEvent {

    /** 事件唯一标识，消费端用它做幂等。 */
    String eventId();

    /** 产生该事件的聚合根 ID。 */
    String aggregateId();

    /** 事件发生时间（业务时间，非入库时间）。 */
    Instant occurredOn();

    /** 事件类型名，默认取类名，便于日志与事件存储检索。 */
    default String eventType() {
        return getClass().getSimpleName();
    }
}
