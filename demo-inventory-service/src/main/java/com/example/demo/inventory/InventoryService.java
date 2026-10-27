package com.example.demo.inventory;

import com.example.demo.inventory.domain.Stock;
import com.example.demo.inventory.domain.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.TreeMap;

/**
 * 库存服务（库存限界上下文的核心）。
 *
 * <p>它与订单上下文只有「事件契约」这一条耦合：
 * 订单不知道库存存在，库存也不知道订单的内部模型。</p>
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final StockRepository stockRepository;

    public InventoryService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    /** 预占库存，成功返回 true。 */
    @Transactional
    public boolean reserve(String productId, int quantity) {
        Stock stock = stockRepository.findByIdForUpdate(productId).orElse(null);
        if (stock == null) {
            log.warn("[库存] 预占失败，商品不存在 productId={}", productId);
            return false;
        }
        if (!stock.canReserve(quantity)) {
            log.warn("[库存] 预占失败，库存不足 productId={} 需要={} 可用={}",
                    productId, quantity, stock.available());
            return false;
        }
        stockRepository.save(stock.reserve(quantity));
        log.info("[库存] 预占成功 productId={} 数量={} 剩余={}",
                productId, quantity, stock.available() - quantity);
        return true;
    }

    /** 释放库存（订单取消时补偿）。只释放实际预占过的数量，避免未预占成功的明细虚增库存。 */
    @Transactional
    public void release(String productId, int quantity) {
        Stock stock = stockRepository.findByIdForUpdate(productId).orElse(null);
        if (stock == null) {
            log.warn("[库存] 忽略释放：商品不存在 productId={}", productId);
            return;
        }
        Stock released = stock.release(quantity);
        if (released.reserved() == stock.reserved()) {
            log.warn("[库存] 忽略释放：该商品无预占记录 productId={} 请求释放={}", productId, quantity);
            return;
        }
        stockRepository.save(released);
        log.info("[库存] 已释放 productId={} 数量={} 当前可用={}",
                productId, stock.reserved() - released.reserved(), released.available());
    }

    @Transactional(readOnly = true)
    public int available(String productId) {
        return stockRepository.findById(productId).map(Stock::available).orElse(0);
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> stockSnapshot() {
        Map<String, Integer> snapshot = new TreeMap<>();
        stockRepository.findAll().forEach(stock -> snapshot.put(stock.productId(), stock.available()));
        return snapshot;
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> reservedSnapshot() {
        Map<String, Integer> snapshot = new TreeMap<>();
        stockRepository.findAll().forEach(stock -> snapshot.put(stock.productId(), stock.reserved()));
        return snapshot;
    }
}
