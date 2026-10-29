-- 会话 A：lock id=1 first, then id=2 (blocked by B)
USE demo_mysql_principle;
SET SESSION innodb_lock_wait_timeout = 30;
BEGIN;
UPDATE deadlock_demo SET v = v + 1 WHERE id = 1;
SELECT 'A: 持有 id=1 的行锁' AS tag, SLEEP(2) AS waited;
UPDATE deadlock_demo SET v = v + 1 WHERE id = 2;   -- blocked by B holding id=2
COMMIT;
SELECT 'A: 提交后' AS tag, id, v FROM deadlock_demo ORDER BY id;
