-- ============================================================
-- 实验二补充 v2：覆盖索引逻辑读对比（输出单行，便于截图）
-- ============================================================
USE demo_mysql_principle;

-- 路径 A（回表）：SUM(LENGTH(bio)) 必须取整行 → 3005 次回聚簇索引
FLUSH STATUS;
SELECT SUM(LENGTH(bio)) AS total_bio_len FROM emp WHERE dept_id = 7;
SHOW SESSION STATUS LIKE 'Innodb_buffer_pool_read_requests';
EXPLAIN SELECT SUM(LENGTH(bio)) FROM emp WHERE dept_id = 7;

-- 路径 B（覆盖）：只算 (dept_id, id)，idx_dept 叶子节点全有 → 零回表
FLUSH STATUS;
SELECT SUM(id) + SUM(dept_id) AS cover_sum FROM emp WHERE dept_id = 7;
SHOW SESSION STATUS LIKE 'Innodb_buffer_pool_read_requests';
EXPLAIN SELECT SUM(id) + SUM(dept_id) FROM emp WHERE dept_id = 7;

-- 稳定性：A、B 各再跑一遍
FLUSH STATUS;
SELECT SUM(LENGTH(bio)) FROM emp WHERE dept_id = 7;
SHOW SESSION STATUS LIKE 'Innodb_buffer_pool_read_requests';
FLUSH STATUS;
SELECT SUM(id) + SUM(dept_id) FROM emp WHERE dept_id = 7;
SHOW SESSION STATUS LIKE 'Innodb_buffer_pool_read_requests';

-- ---------- name 前缀 LIKE：有索引 vs 前导 % ----------
EXPLAIN SELECT * FROM emp WHERE name LIKE 'emp-999%';
EXPLAIN SELECT * FROM emp WHERE name LIKE '%999';
