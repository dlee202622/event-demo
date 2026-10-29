-- 会话 B：t=1s 尝试向间隙里插 id=7，应被阻塞到 A 提交
USE demo_mysql_principle;
SELECT SLEEP(1) AS b_wait;
SET SESSION innodb_lock_wait_timeout = 20;
SET @t = NOW(6);
INSERT INTO gap_demo VALUES (7, 'inserted-by-B');
SELECT 'B: id=7 插入成功' AS tag,
       TIMESTAMPDIFF(MICROSECOND, @t, NOW(6))/1e6 AS insert_waited_sec;
