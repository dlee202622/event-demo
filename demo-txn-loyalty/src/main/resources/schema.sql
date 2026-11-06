-- 积分服务（C）的全部表。CREATE TABLE IF NOT EXISTS：重启可重复执行。

-- 积分账户。frozen 是 TCC 冻结额度：Try 把 balance 转入 frozen，Confirm 永久扣掉，
-- Cancel 把 frozen 还回 balance。与 B 的 stock 表结构对齐，便于教程对照讲解。
CREATE TABLE IF NOT EXISTS points_account (
    customer_id VARCHAR(32) PRIMARY KEY,
    balance     INT          NOT NULL COMMENT '可用积分',
    frozen      INT NOT NULL DEFAULT 0 COMMENT 'TCC 冻结额度',
    updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB;

-- 积分流水：Saga / 本地消息表的扣减与补偿各记一条，审计「扣了几次、补了几次」。
CREATE TABLE IF NOT EXISTS points_ledger (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id VARCHAR(32) NOT NULL,
    delta       INT         NOT NULL COMMENT '积分变动量，负=扣减，正=补偿',
    type        VARCHAR(20) NOT NULL COMMENT 'DEDUCT / COMPENSATE',
    msg_id      VARCHAR(64) NULL COMMENT '幂等键，Saga/本地消息表路径使用',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB;

-- 2PC 参与者日志：Prepare 与业务扣减同事务，协调者宕机后的恢复依据。
CREATE TABLE IF NOT EXISTS txn_prepare_log (
    xid         VARCHAR(64) NOT NULL,
    state       VARCHAR(16) NOT NULL COMMENT 'PREPARED / COMMITTED / ROLLED_BACK',
    customer_id VARCHAR(32) NOT NULL,
    points      INT         NOT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    decided_at  DATETIME(3) NULL,
    PRIMARY KEY (xid)
) ENGINE = InnoDB;

-- TCC 分支事务日志。与 B 不同：customer_id 存进日志，Cancel 不需要猜测还原对象。
CREATE TABLE IF NOT EXISTS tcc_log (
    xid         VARCHAR(64) NOT NULL,
    branch      VARCHAR(32) NOT NULL COMMENT '本服务固定 loyalty',
    state       VARCHAR(20) NOT NULL COMMENT 'TRIED / CONFIRMED / CANCELLED / CANCELLED_EMPTY(空回滚占位)',
    customer_id VARCHAR(32) NOT NULL,
    points      INT         NOT NULL COMMENT '本次冻结的积分，Cancel 还原用',
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (xid, branch)
) ENGINE = InnoDB;

-- 消息幂等表：与 B 的 processed_msg 对齐，msgId 去重保证消息重投只扣一次。
CREATE TABLE IF NOT EXISTS processed_msg (
    msg_id       VARCHAR(64) PRIMARY KEY,
    detail       VARCHAR(255) NOT NULL,
    processed_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB;

-- Seata AT 的分支回滚日志（官方表结构）。
CREATE TABLE IF NOT EXISTS undo_log (
    branch_id     BIGINT       NOT NULL COMMENT 'branch transaction id',
    xid           VARCHAR(128) NOT NULL COMMENT 'global transaction id',
    context       VARCHAR(128) NOT NULL COMMENT 'undo_log context, such as serialization',
    rollback_info LONGBLOB     NOT NULL COMMENT 'rollback info',
    log_status    INT          NOT NULL COMMENT '0:normal status,1:defense status',
    log_created   DATETIME(6)  NOT NULL COMMENT 'create datetime',
    log_modified  DATETIME(6)  NOT NULL COMMENT 'modify datetime',
    UNIQUE KEY ux_undo_log (xid, branch_id)
) ENGINE = InnoDB COMMENT = 'AT transaction mode undo table';
