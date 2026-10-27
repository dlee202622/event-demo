package com.example.demo.eventdriven.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 聚合根基类：负责收集聚合内部产生的领域事件。
 *
 * <p>核心思想是「事件先登记、后发布」：聚合在业务方法里只做
 * {@link #registerEvent(DomainEvent)}，不直接依赖任何事件总线；
 * 由应用层在持久化成功之后统一调用 {@link #pullDomainEvents()} 并发布。
 * 这样领域层保持纯净（零框架依赖），也不会出现「事件先发出去、数据却写失败」的问题。</p>
 *
 * <p>注意：领域事件列表不参与聚合的相等性判断，也不应被序列化到数据库。</p>
 */
public abstract class AggregateRoot {

    private final transient List<DomainEvent> domainEvents = new ArrayList<>();

    /** 登记一个领域事件（子类在业务方法中调用）。 */
    protected void registerEvent(DomainEvent event) {
        this.domainEvents.add(event);
    }

    /** 取出并清空已登记的领域事件（发布后必须清空，避免重复投递）。 */
    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> events = List.copyOf(this.domainEvents);
        this.domainEvents.clear();
        return events;
    }

    /** 是否还有未发布的事件，供仓储/应用层断言使用。 */
    public boolean hasUnpublishedEvents() {
        return !this.domainEvents.isEmpty();
    }

    /** 只读查看，不消费事件（用于测试或调试）。 */
    public List<DomainEvent> peekDomainEvents() {
        return List.copyOf(this.domainEvents);
    }
}
