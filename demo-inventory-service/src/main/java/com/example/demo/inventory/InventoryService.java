package com.example.demo.inventory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 库存服务（库存限界上下文的核心）。
 *
 * <p>它与订单上下文只有「事件契约」这一条耦合：
 * 订单不知道库存存在，库存也不知道订单的内部模型。</p>
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final Map<String, Integer> stock = new ConcurrentHashMap<>();
    private final Map<String, Integer> reserved = new ConcurrentHashMap<>();

    public InventoryService() {
        // 演示用初始库存
        stock.put("P-1001", 100);
        stock.put("P-1002", 50);
        stock.put("P-1003", 20);
    }

    /** 预占库存，成功返回 true。 */
    public synchronized boolean reserve(String productId, int quantity) {
        int available = stock.getOrDefault(productId, 0);
        if (available < quantity) {
            log.warn("[库存] 预占失败，库存不足 productId={} 需要={} 可用={}", productId, quantity, available);
            return false;
        }
        stock.put(productId, available - quantity);
        reserved.merge(productId, quantity, Integer::sum);
        log.info("[库存] 预占成功 productId={} 数量={} 剩余={}", productId, quantity, available - quantity);
        return true;
    }

    /** 释放库存（订单取消时补偿）。只释放实际预占过的数量，避免未预占成功的明细虚增库存。 */
    public synchronized void release(String productId, int quantity) {
        int reservedQty = reserved.getOrDefault(productId, 0);
        int actual = Math.min(quantity, reservedQty);
        if (actual <= 0) {
            log.warn("[库存] 忽略释放：该商品无预占记录 productId={} 请求释放={}", productId, quantity);
            return;
        }
        stock.merge(productId, actual, Integer::sum);
        reserved.computeIfPresent(productId, (key, value) -> Math.max(0, value - actual));
        log.info("[库存] 已释放 productId={} 数量={} 当前可用={}", productId, actual, stock.get(productId));
    }

    public int available(String productId) {
        return stock.getOrDefault(productId, 0);
    }

    public Map<String, Integer> stockSnapshot() {
        return new TreeMap<>(stock);
    }

    public Map<String, Integer> reservedSnapshot() {
        return new TreeMap<>(reserved);
    }

    /** 仅测试/演示用：恢复初始库存。 */
    public synchronized void reset() {
        stock.clear();
        reserved.clear();
        stock.put("P-1001", 100);
        stock.put("P-1002", 50);
        stock.put("P-1003", 20);
    }
}
