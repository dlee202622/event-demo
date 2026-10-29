-- 会话 A：READ-COMMITTED 下的对比（B 会把 v 改成 3）
USE demo_mysql_principle;
SET SESSION transaction_isolation = 'READ-COMMITTED';
BEGIN;
SELECT 'A@t0(快照读)' AS tag, v, note FROM mvcc_demo WHERE id = 1;
SELECT SLEEP(4) AS a_wait;
SELECT 'A@t1(事务内快照读,B已提交)' AS tag, v, note FROM mvcc_demo WHERE id = 1;
COMMIT;
