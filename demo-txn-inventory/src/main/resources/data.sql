-- 种子数据：幂等写法，实验反复跑不会叠加。
-- 库存固定重置为 20 / 0，教程里每次实验前可通过 /api/stock/reset 复位。
INSERT INTO stock (product_id, available, frozen)
VALUES ('P-1001', 20, 0)
ON DUPLICATE KEY UPDATE available = VALUES(available),
                        frozen    = VALUES(frozen);
