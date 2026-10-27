package com.example.demo.inventory.domain;

/**
 * 单个商品的库存快照。
 *
 * <p>available + reserved 的变更都通过值对象方法生成新快照，避免散落的加减逻辑。</p>
 */
public record Stock(String productId, int available, int reserved) {

    public Stock {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("商品 ID 不能为空");
        }
        if (available < 0 || reserved < 0) {
            throw new IllegalArgumentException("库存数量不能为负数");
        }
    }

    public boolean canReserve(int quantity) {
        return available >= quantity;
    }

    public Stock reserve(int quantity) {
        return new Stock(productId, available - quantity, reserved + quantity);
    }

    /** 释放数量以实际预占为上限，避免未预占成功的明细虚增库存。 */
    public Stock release(int quantity) {
        int actual = Math.min(quantity, reserved);
        return new Stock(productId, available + actual, reserved - actual);
    }
}
