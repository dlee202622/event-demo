#!/bin/zsh
# 分布式事务 demo · 实验脚本：A=9008 订单 / B=9009 库存 / C=9010 积分
# 用法：./run-experiments.sh [case ...]   不带参数跑全部
# 前置：docker compose up -d mysql seata-server；三个服务已启动
set -u
A=http://localhost:9008
B=http://localhost:9009
C=http://localhost:9010

req() { # req METHOD URL [curl 参数...] —— 打印命令与响应，便于教学对照
  local method=$1 url=$2; shift 2
  echo ">>> ${method} ${url}"
  curl -s --noproxy '*' -X "$method" "$url" "$@"
  echo
}

reset_all() {
  req POST $B/api/stock/reset
  req POST $C/api/points/reset
  req POST "$B/api/fault?mode=NONE"
  req POST "$C/api/fault?mode=NONE"
}

case_2pc_commit() {
  echo "########## 2PC · 全员 YES → 全局提交 ##########"
  reset_all
  req POST "$A/api/txn2pc/order?qty=1&points=10"
  req GET  $B/api/stock/P-1001
  req GET  $C/api/points/C-1001
}

case_2pc_rollback() {
  echo "########## 2PC · C 投 NO → 全局回滚 ##########"
  reset_all
  req POST "$C/api/fault?mode=FAIL"
  req POST "$A/api/txn2pc/order?qty=1&points=10"
  req GET  $B/api/stock/P-1001
  req GET  $C/api/points/C-1001
  req POST "$C/api/fault?mode=NONE"
}

case_2pc_crash() {
  echo "########## 2PC · 协调者宕机 → in-doubt → 人工决议 ##########"
  reset_all
  req POST "$A/api/txn2pc/order?qty=1&points=10&crashAfterPrepare=true"
  req GET  $A/api/txn2pc/pending
  req GET  $B/api/txn2pc/status
  echo ">>> 锁探测（Prepare 未决，NOWAIT 应立即失败）"
  req POST $B/api/stock/P-1001/lock-check
  echo ">>> 快照读（未提交不可见，available 仍应为 19 之前的 20）"
  req GET  $B/api/stock/P-1001
  req POST "$A/api/txn2pc/resolve?xid=PLACEHOLDER&decision=commit"
}

case_tcc_commit() {
  echo "########## TCC · Try 全成功 → Confirm ##########"
  reset_all
  req POST "$A/api/tcc/order?qty=1&points=10"
  req GET  $B/api/stock/P-1001
  req GET  $C/api/points/C-1001
}

case_tcc_cancel() {
  echo "########## TCC · C Try 失败 → Cancel 解冻 ##########"
  reset_all
  req POST "$C/api/fault?mode=FAIL"
  req POST "$A/api/tcc/order?qty=1&points=10"
  req GET  $B/api/stock/P-1001
  req GET  $C/api/points/C-1001
  req POST "$C/api/fault?mode=NONE"
}

case_tcc_anomalies() {
  echo "########## TCC · 空回滚 / 悬挂 / 幂等（以 C 积分侧演示） ##########"
  reset_all
  req POST "$C/api/tcc/guard?enabled=true"
  echo "--- 空回滚：Try 未到，Cancel 先来 ---"
  req POST $C/api/tcc/cancel/manual-anomaly-1
  echo "--- 悬挂防护：迟到的 Try 被拒绝 ---"
  req POST "$C/api/tcc/try/manual-anomaly-1?points=10"
  req GET  $C/api/points/C-1001
  echo "--- 关闭防护复现悬挂：Cancel 不占位 ---"
  req POST "$C/api/tcc/guard?enabled=false"
  req POST $C/api/tcc/cancel/manual-anomaly-2
  req POST "$C/api/tcc/try/manual-anomaly-2?points=10"
  req GET  $C/api/points/C-1001
  echo "--- 收尾：恢复防护并清掉孤儿冻结 ---"
  req POST "$C/api/tcc/guard?enabled=true"
  req POST $C/api/tcc/cancel/manual-anomaly-2
  req GET  $C/api/tcc/logs
  reset_all
}

case_saga() {
  echo "########## Saga · 无隔离性窗口观测 ##########"
  reset_all
  echo "--- 正常路径：正向链全部成功 ---"
  req POST "$A/api/saga/order?qty=1&points=10"
  echo "--- C 失败：补偿前的窗口内 B 已扣减（对外可见） ---"
  req POST "$C/api/fault?mode=FAIL"
  ( sleep 1.2; echo ">>> [窗口内] B 的库存（available 已 -1，无隔离性）"; req GET $B/api/stock/P-1001 ) &
  req POST "$A/api/saga/order?qty=1&points=10&compensateDelayMs=2000"
  wait
  echo "--- 补偿后恢复 ---"
  req GET $B/api/stock/P-1001
  req GET $C/api/points/C-1001
  req GET $C/api/points/C-1001/ledger
  req POST "$C/api/fault?mode=NONE"
}

case_localmsg() {
  echo "########## 本地消息表 · C 宕机 → 消息积压 → 恢复收敛 ##########"
  reset_all
  req POST "$C/api/fault?mode=FAIL"
  req POST "$A/api/localmsg/order?qty=1&points=10"
  sleep 4
  echo ">>> 4 秒后：消息 PENDING 重试中（C 仍故障）"
  req GET  "$A/api/localmsg/messages?limit=3"
  req POST "$C/api/fault?mode=NONE"
  sleep 3
  echo ">>> 恢复 3 秒后：消息 SENT、订单 CONFIRMED"
  req GET  "$A/api/localmsg/messages?limit=3"
  req GET  "$A/api/localmsg/orders?limit=3"
  req GET  $B/api/stock/P-1001
  req GET  $C/api/points/C-1001
}

case_seata() {
  echo "########## Seata AT · 全局提交与自动反向补偿 ##########"
  reset_all
  echo "--- 全局提交：三个分支一起生效 ---"
  req POST "$A/api/seata/order?qty=1&points=10"
  req GET  $B/api/stock/P-1001
  req GET  $C/api/points/C-1001
  req GET  $B/api/seata/status
  echo "--- 全局回滚：C 失败，B 被 undo_log 自动补偿，订单行被回滚 ---"
  req POST "$C/api/fault?mode=FAIL"
  req POST "$A/api/seata/order?qty=1&points=10"
  req GET  $B/api/stock/P-1001
  req GET  $C/api/points/C-1001
  req POST "$C/api/fault?mode=NONE"
}

all_cases="2pc_commit 2pc_rollback 2pc_crash tcc_commit tcc_cancel tcc_anomalies saga localmsg seata"
targets=${*:-$all_cases}
for t in ${(z)targets}; do "case_$t"; done
