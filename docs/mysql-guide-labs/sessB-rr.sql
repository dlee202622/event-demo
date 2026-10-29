-- 会话 B：t=1s 提交一次更新
USE demo_mysql_principle;
SELECT SLEEP(1) AS b_wait;
UPDATE mvcc_demo SET v = 2, note = 'changed-by-B' WHERE id = 1;
SELECT 'B@t1(已提交)' AS tag, v, note FROM mvcc_demo WHERE id = 1;
