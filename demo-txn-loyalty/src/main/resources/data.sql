-- 种子数据：幂等写法。客户 C-1001 固定 100 积分，实验可反复覆盖。
INSERT INTO points_account (customer_id, balance, frozen)
VALUES ('C-1001', 100, 0)
ON DUPLICATE KEY UPDATE balance = VALUES(balance),
                        frozen  = VALUES(frozen);
