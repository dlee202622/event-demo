-- 会话 A：对 [5,15] 区间加临键锁（含间隙），持有 3 秒
USE demo_mysql_principle;
BEGIN;
SELECT id, note FROM gap_demo WHERE id BETWEEN 5 AND 15 FOR UPDATE;
SELECT 'A: 持有 [5,15] 区间的间隙锁+行锁' AS tag, SLEEP(3) AS waited;
COMMIT;
