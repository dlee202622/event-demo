-- ============================================================
-- 实验二：回表 vs 覆盖索引 / 最左前缀 / 索引失效 / 深分页
-- ============================================================
USE demo_mysql_principle;

-- ---------- 员工表：300k 行，bio 600B 撑宽行 ----------
DROP TABLE IF EXISTS emp;
CREATE TABLE emp (
  id      BIGINT       NOT NULL AUTO_INCREMENT,
  dept_id INT          NOT NULL,           -- 100 个部门，每部门约 3000 行
  name    VARCHAR(64)  NOT NULL,
  bio     VARCHAR(600) NOT NULL,           -- 模拟宽行，放大回表成本
  status  TINYINT      NOT NULL DEFAULT 0, -- 低区分度列
  phone   VARCHAR(20)  NOT NULL DEFAULT '',
  PRIMARY KEY (id),
  KEY idx_dept   (dept_id),
  KEY idx_status (status)
) ENGINE=InnoDB;

SET @t = NOW(6);
INSERT INTO emp (dept_id, name, bio, status, phone)
SELECT FLOOR(RAND(n)*100),
       CONCAT('emp-', n),
       RPAD(SHA(n), 600, 'x'),
       n % 2,
       CONCAT('13', LPAD(n % 100000000, 8, '0'))
FROM nums WHERE n <= 300000;
SELECT TIMESTAMPDIFF(MICROSECOND, @t, NOW(6))/1e6 AS emp_insert_sec;

-- ---------- 2.1 回表 vs 覆盖索引 ----------
-- 回表：SELECT * 要拿整行，二级索引里只有 (dept_id, id)，必须逐行回聚簇索引
EXPLAIN SELECT * FROM emp WHERE dept_id = 7;

-- 覆盖：idx_dept 的叶子节点本身含 (dept_id, id)，不回表
EXPLAIN SELECT id, dept_id FROM emp WHERE dept_id = 7;

-- 计时对比：各跑 30 次
DROP PROCEDURE IF EXISTS time_lookup;
DELIMITER //
CREATE PROCEDURE time_lookup()
BEGIN
  DECLARE i INT DEFAULT 0; DECLARE cnt BIGINT DEFAULT 0;
  SET @t = NOW(6);
  WHILE i < 30 DO
    SET cnt = cnt + (SELECT COUNT(*) FROM (SELECT * FROM emp WHERE dept_id = 7) x);
    SET i = i + 1;
  END WHILE;
  SELECT TIMESTAMPDIFF(MICROSECOND, @t, NOW(6))/1e6 AS select_star_30x_sec, cnt;

  SET i = 0; SET cnt = 0;
  SET @t = NOW(6);
  WHILE i < 30 DO
    SET cnt = cnt + (SELECT COUNT(*) FROM (SELECT id, dept_id FROM emp WHERE dept_id = 7) x);
    SET i = i + 1;
  END WHILE;
  SELECT TIMESTAMPDIFF(MICROSECOND, @t, NOW(6))/1e6 AS cover_30x_sec, cnt;
END//
DELIMITER ;
CALL time_lookup();

-- ---------- 2.2 联合索引最左前缀 ----------
DROP TABLE IF EXISTS idx_abc;
CREATE TABLE idx_abc (
  a INT NOT NULL, b INT NOT NULL, c INT NOT NULL, d VARCHAR(16) NOT NULL,
  KEY idx_abc (a, b, c)
) ENGINE=InnoDB;
INSERT INTO idx_abc
SELECT FLOOR(RAND(n)*10), FLOOR(RAND(n+7)*100), FLOOR(RAND(n+13)*1000), RPAD('d',16,'x')
FROM nums WHERE n <= 200000;
ANALYZE TABLE idx_abc;

EXPLAIN SELECT * FROM idx_abc WHERE a = 5;
EXPLAIN SELECT * FROM idx_abc WHERE a = 5 AND b = 50;
EXPLAIN SELECT * FROM idx_abc WHERE a = 5 AND b = 50 AND c = 500;
EXPLAIN SELECT * FROM idx_abc WHERE b = 50;
EXPLAIN SELECT * FROM idx_abc WHERE c = 500;
EXPLAIN SELECT * FROM idx_abc WHERE a = 5 AND c = 500;

-- ---------- 2.3 索引失效四连 ----------
ALTER TABLE emp ADD KEY idx_phone (phone);
ANALYZE TABLE emp;

EXPLAIN SELECT * FROM emp WHERE phone = '13812345678';   -- 正常 ref
EXPLAIN SELECT * FROM emp WHERE phone = 13812345678;     -- 隐式类型转换
EXPLAIN SELECT * FROM emp WHERE LEFT(phone, 3) = '138';  -- 函数包裹列
EXPLAIN SELECT * FROM emp WHERE name LIKE '%999';        -- 前导 %
EXPLAIN SELECT * FROM emp WHERE name LIKE 'emp-999%';    -- 前缀匹配可用索引
EXPLAIN SELECT * FROM emp WHERE status = 1;              -- 区分度极低

-- ---------- 2.4 深分页 ----------
DROP TABLE IF EXISTS orders_big;
CREATE TABLE orders_big (
  id      BIGINT       NOT NULL AUTO_INCREMENT,
  user_id BIGINT       NOT NULL,
  amount  DECIMAL(10,2) NOT NULL,
  note    VARCHAR(200) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_user (user_id)
) ENGINE=InnoDB;
SET @t = NOW(6);
INSERT INTO orders_big (user_id, amount, note)
SELECT FLOOR(RAND(n)*100000), ROUND(RAND(n+3)*1000, 2), RPAD('o',200,'x')
FROM nums WHERE n <= 1000000;
SELECT TIMESTAMPDIFF(MICROSECOND, @t, NOW(6))/1e6 AS orders_insert_sec;
ANALYZE TABLE orders_big;

DROP PROCEDURE IF EXISTS time_page;
DELIMITER //
CREATE PROCEDURE time_page()
BEGIN
  DECLARE i INT DEFAULT 0; DECLARE cnt BIGINT DEFAULT 0;
  SET @t = NOW(6);
  WHILE i < 5 DO
    SET cnt = cnt + (SELECT COUNT(*) FROM (SELECT * FROM orders_big ORDER BY id LIMIT 900000, 10) x);
    SET i = i + 1;
  END WHILE;
  SELECT TIMESTAMPDIFF(MICROSECOND, @t, NOW(6))/1e6 AS deep_offset_5x_sec, cnt;

  SET i = 0; SET cnt = 0;
  SET @t = NOW(6);
  WHILE i < 5 DO
    SET cnt = cnt + (SELECT COUNT(*) FROM (SELECT * FROM orders_big WHERE id > 900000 ORDER BY id LIMIT 10) x);
    SET i = i + 1;
  END WHILE;
  SELECT TIMESTAMPDIFF(MICROSECOND, @t, NOW(6))/1e6 AS cursor_5x_sec, cnt;

  SET i = 0; SET cnt = 0;
  SET @t = NOW(6);
  WHILE i < 5 DO
    SET cnt = cnt + (SELECT COUNT(*) FROM (SELECT o.* FROM orders_big o
        JOIN (SELECT id FROM orders_big ORDER BY id LIMIT 900000, 10) t ON o.id = t.id) x);
    SET i = i + 1;
  END WHILE;
  SELECT TIMESTAMPDIFF(MICROSECOND, @t, NOW(6))/1e6 AS late_lookup_5x_sec, cnt;
END//
DELIMITER ;
CALL time_page();
