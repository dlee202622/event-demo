-- 会话 A：REPEATABLE-READ 下的快照读 vs 当前读
USE demo_mysql_principle;
SET SESSION transaction_isolation = 'REPEATABLE-READ';
BEGIN;
SELECT 'A@t0(快照读)' AS tag, v, note FROM mvcc_demo WHERE id = 1;
SELECT SLEEP(4) AS a_wait;
SELECT 'A@t1(事务内快照读,B已提交)' AS tag, v, note FROM mvcc_demo WHERE id = 1;
SELECT 'A@t2(当前读 FOR UPDATE)' AS tag, v, note FROM mvcc_demo WHERE id = 1 FOR UPDATE;
COMMIT;
SELECT 'A@t3(提交后新快照)' AS tag, v, note FROM mvcc_demo WHERE id = 1;
