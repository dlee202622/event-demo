#!/bin/bash
# MVCC / 锁实验编排：双会话并发
set -u
CID=$(docker compose ps -q mysql)
MY() { docker exec -i "$CID" mysql -uroot -p123456 -t; }

# 初始化 MVCC 演示表
docker exec -i "$CID" mysql -uroot -p123456 2>/dev/null <<'EOF'
DROP TABLE IF EXISTS demo_mysql_principle.mvcc_demo;
CREATE TABLE demo_mysql_principle.mvcc_demo (id BIGINT PRIMARY KEY, v INT NOT NULL, note VARCHAR(64) NOT NULL) ENGINE=InnoDB;
INSERT INTO demo_mysql_principle.mvcc_demo VALUES (1, 1, 'init');
EOF

# RR：A 事务 BEGIN 后停 4 秒，B 在 t=1s 提交更新
MY < /private/tmp/mysql-exp/sessA-rr.sql > /private/tmp/mysql-exp/out-rr-A.txt 2>/dev/null &
P1=$!
MY < /private/tmp/mysql-exp/sessB-rr.sql > /private/tmp/mysql-exp/out-rr-B.txt 2>/dev/null &
wait $P1
echo "===== RR: Session A ====="; cat /private/tmp/mysql-exp/out-rr-A.txt
echo "===== RR: Session B ====="; cat /private/tmp/mysql-exp/out-rr-B.txt

# RC：同样时序
MY < /private/tmp/mysql-exp/sessA-rc.sql > /private/tmp/mysql-exp/out-rc-A.txt 2>/dev/null &
P2=$!
MY < /private/tmp/mysql-exp/sessB-rc.sql > /private/tmp/mysql-exp/out-rc-B.txt 2>/dev/null &
wait $P2
echo "===== RC: Session A ====="; cat /private/tmp/mysql-exp/out-rc-A.txt
echo "===== RC: Session B ====="; cat /private/tmp/mysql-exp/out-rc-B.txt
