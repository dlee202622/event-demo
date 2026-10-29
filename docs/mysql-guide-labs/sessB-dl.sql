-- 会话 B：lock id=2 first, then id=1 forms the cycle
USE demo_mysql_principle;
SET SESSION innodb_lock_wait_timeout = 30;
BEGIN;
UPDATE deadlock_demo SET v = v + 1 WHERE id = 2;
SELECT 'B: 持有 id=2 的行锁' AS tag, SLEEP(2.5) AS waited;
UPDATE deadlock_demo SET v = v + 1 WHERE id = 1;   -- deadlock point
ROLLBACK;
