package com.example.demo.shared.infrastructure.idempotency;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消费端幂等登记表（内存版）。
 *
 * <p>事件驱动链路默认是「至少一次（at-least-once）」语义：
 * 网络重试、Outbox 重投、消费者重启都会造成重复投递。
 * 因此**每一个事件消费者都必须幂等**，本类用 eventId 做去重。</p>
 *
 * <p>生产实现：Redis SETNX（带 TTL）或数据库唯一索引
 * {@code unique(event_id, consumer_group)}。</p>
 */
@Component
public class ProcessedEventRegistry {

    private final Set<String> processed = ConcurrentHashMap.newKeySet();

    /** 首次处理返回 true；重复事件返回 false。 */
    public boolean markIfAbsent(String eventId) {
        return processed.add(eventId);
    }

    public int size() {
        return processed.size();
    }

    public void clear() {
        processed.clear();
    }
}
