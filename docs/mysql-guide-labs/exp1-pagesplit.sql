-- ============================================================
-- 实验一：B+ 树页分裂 —— 递增主键 vs UUID 随机主键
-- 环境：mysql 8.4.13 / InnoDB / 页 16KB
-- ============================================================
CREATE DATABASE IF NOT EXISTS demo_mysql_principle;
USE demo_mysql_principle;

-- 生成 1~1048576 的数字序列（倍增法，比递归 CTE 快）
DROP TABLE IF EXISTS nums;
CREATE TABLE nums (n BIGINT NOT NULL, PRIMARY KEY (n)) ENGINE=InnoDB;
INSERT INTO nums VALUES (1);

DROP PROCEDURE IF EXISTS fill_nums;
DELIMITER //
CREATE PROCEDURE fill_nums()
BEGIN
  WHILE (SELECT COUNT(*) FROM nums) < 1048576 DO
    INSERT INTO nums SELECT n + (SELECT MAX(n) FROM nums) FROM nums;
  END WHILE;
END//
DELIMITER ;
CALL fill_nums();
SELECT COUNT(*) AS nums_rows FROM nums;

-- ---------- 表 1：递增主键（数据按主键顺序到达） ----------
DROP TABLE IF EXISTS inc_pk;
CREATE TABLE inc_pk (
  id      BIGINT       NOT NULL,
  name    VARCHAR(32)  NOT NULL,
  padding VARCHAR(128) NOT NULL,          -- 撑宽行，让每页放的行数可观察
  PRIMARY KEY (id)
) ENGINE=InnoDB;

SET @t = NOW(6);
INSERT INTO inc_pk
SELECT n, CONCAT('user-', n), RPAD('p', 128, 'x')
FROM nums WHERE n <= 300000 ORDER BY n;   -- 主键升序插入
SELECT TIMESTAMPDIFF(MICROSECOND, @t, NOW(6))/1e6 AS inc_insert_sec;

-- ---------- 表 2：UUID 主键（数据按随机顺序到达） ----------
DROP TABLE IF EXISTS uuid_pk;
CREATE TABLE uuid_pk (
  id      CHAR(32)     NOT NULL,           -- MD5，模拟随机主键
  src     BIGINT       NOT NULL,
  name    VARCHAR(32)  NOT NULL,
  padding VARCHAR(128) NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB;

SET @t = NOW(6);
INSERT INTO uuid_pk
SELECT MD5(n), n, CONCAT('user-', n), RPAD('p', 128, 'x')
FROM nums WHERE n <= 300000 ORDER BY MD5(n);  -- 随机顺序到达
SELECT TIMESTAMPDIFF(MICROSECOND, @t, NOW(6))/1e6 AS uuid_insert_sec;

-- ---------- 结构对比：文件大小 / 数据页占用 / 碎片 ----------
SELECT NAME, FILE_SIZE/1024/1024 AS file_mb
FROM information_schema.INNODB_TABLESPACES
WHERE NAME IN ('demo_mysql_principle/inc_pk', 'demo_mysql_principle/uuid_pk')
ORDER BY NAME;

SHOW TABLE STATUS IN demo_mysql_principle WHERE Name IN ('inc_pk','uuid_pk')\G

SELECT TABLE_NAME, INDEX_NAME, SUB_PART, CARDINALITY
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA='demo_mysql_principle' AND TABLE_NAME IN ('inc_pk','uuid_pk');
