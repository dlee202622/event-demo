#!/bin/bash
# 死锁 + 间隙锁实验
set -u
CID=$(docker compose ps -q mysql)
MY() { docker exec -i "$CID" mysql -uroot -p123456 -t; }

# 初始化表
docker exec -i "$CID" mysql -uroot -p123456 2>/dev/null <<'EOF'
DROP TABLE IF EXISTS demo_mysql_principle.deadlock_demo;
CREATE TABLE demo_mysql_principle.deadlock_demo (id BIGINT PRIMARY KEY, v INT NOT NULL) ENGINE=InnoDB;
INSERT INTO demo_mysql_principle.deadlock_demo VALUES (1,0),(2,0);
DROP TABLE IF EXISTS demo_mysql_principle.gap_demo;
CREATE TABLE demo_mysql_principle.gap_demo (id BIGINT PRIMARY KEY, note VARCHAR(32) NOT NULL) ENGINE=InnoDB;
INSERT INTO demo_mysql_principle.gap_demo VALUES (1,'r1'),(5,'r5'),(10,'r10'),(20,'r20');
EOF

echo "===== 死锁实验 ====="
MY < /private/tmp/mysql-exp/sessA-dl.sql > /private/tmp/mysql-exp/out-dl-A.txt 2>/tmp/mysql-exp/err-dl-A.txt &
P1=$!
MY < /private/tmp/mysql-exp/sessB-dl.sql > /private/tmp/mysql-exp/out-dl-B.txt 2>/tmp/mysql-exp/err-dl-B.txt &
wait $P1
echo "--- Session A ---"; cat /private/tmp/mysql-exp/out-dl-A.txt
echo "--- Session B (stderr 含死锁报错) ---"; cat /private/tmp/mysql-exp/out-dl-B.txt; cat /tmp/mysql-exp/err-dl-B.txt 2>/dev/null || cat /private/tmp/mysql-exp/err-dl-B.txt

echo ""
echo "===== LATEST DETECTED DEADLOCK ====="
docker exec -i "$CID" mysql -uroot -p123456 2>/dev/null -e "SHOW ENGINE INNODB STATUS\G" | sed -n '/LATEST DETECTED DEADLOCK/,/END OF INNODB/p' | head -50

echo ""
echo "===== 间隙锁实验 ====="
MY < /private/tmp/mysql-exp/sessA-gap.sql > /private/tmp/mysql-exp/out-gap-A.txt 2>/dev/null &
P2=$!
MY < /private/tmp/mysql-exp/sessB-gap.sql > /private/tmp/mysql-exp/out-gap-B.txt 2>/tmp/mysql-exp/err-gap-B.txt &
wait $P2
echo "--- Session A ---"; cat /private/tmp/mysql-exp/out-gap-A.txt
echo "--- Session B ---"; cat /private/tmp/mysql-exp/out-gap-B.txt; cat /tmp/mysql-exp/err-gap-B.txt 2>/dev/null || cat /private/tmp/mysql-exp/err-gap-B.txt
