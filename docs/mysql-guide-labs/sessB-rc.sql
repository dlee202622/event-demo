-- 会话 B：t=1s 把 v 改成 3 并提交
USE demo_mysql_principle;
SELECT SLEEP(1) AS b_wait;
UPDATE mvcc_demo SET v = 3, note = 'changed-by-B-again' WHERE id = 1;
SELECT 'B@t1(已提交)' AS tag, v, note FROM mvcc_demo WHERE id = 1;
