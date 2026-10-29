-- ============================================================
-- 实验三：innodb_flush_log_at_trx_commit = 1 vs 2 的写入耗时
-- 每次 INSERT 都是一个独立事务（autocommit=1），5000 次
-- ============================================================
USE demo_mysql_principle;

DROP TABLE IF EXISTS flush_t;
CREATE TABLE flush_t (id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, v INT NOT NULL) ENGINE=InnoDB;

DROP PROCEDURE IF EXISTS burn_writes;
DELIMITER //
CREATE PROCEDURE burn_writes(IN n INT)
BEGIN
  DECLARE i INT DEFAULT 0;
  SET @t = NOW(6);
  WHILE i < n DO
    INSERT INTO flush_t (v) VALUES (i);
    SET i = i + 1;
  END WHILE;
  SELECT TIMESTAMPDIFF(MICROSECOND, @t, NOW(6))/1e6 AS burn_sec, n AS commits;
END//
DELIMITER ;

-- 先 =2（只刷 OS cache），再 =1（每次提交刷盘），最后恢复 =1
SET GLOBAL innodb_flush_log_at_trx_commit = 2;
SELECT @@innodb_flush_log_at_trx_commit AS current_flush_setting;
CALL burn_writes(5000);
TRUNCATE TABLE flush_t;

SET GLOBAL innodb_flush_log_at_trx_commit = 1;
SELECT @@innodb_flush_log_at_trx_commit AS current_flush_setting;
CALL burn_writes(5000);

-- ============================================================
-- 实验四：Buffer Pool 统计（此时缓存已热）
-- ============================================================
SELECT @@innodb_buffer_pool_size/1024/1024 AS buffer_pool_mb,
       @@innodb_redo_log_capacity/1024/1024 AS redo_capacity_mb;

SHOW GLOBAL STATUS LIKE 'Innodb_buffer_pool_pages_data';
SHOW GLOBAL STATUS LIKE 'Innodb_buffer_pool_pages_total';
SHOW GLOBAL STATUS LIKE 'Innodb_buffer_pool_read_requests';
SHOW GLOBAL STATUS LIKE 'Innodb_buffer_pool_reads';

-- 逻辑读命中率 = 1 - 物理读/逻辑读
SELECT ROUND(100 * (1 - (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_reads')
                     / (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_buffer_pool_read_requests')), 4) AS buffer_pool_hit_pct;
