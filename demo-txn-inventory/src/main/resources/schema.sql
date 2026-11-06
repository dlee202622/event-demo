-- 库存服务（B）的全部表。CREATE TABLE IF NOT EXISTS：重启可重复执行。

-- 库存主表。frozen 是 TCC 的冻结额度：Try 阶段把 available 转移到 frozen，
-- Confirm 永久扣掉 frozen，Cancel 把 frozen 还回 available。
CREATE TABLE IF NOT EXISTS stock (
    product_id VARCHAR(32) PRIMARY KEY,
    available  INT          NOT NULL COMMENT '可售库存',
    frozen     INT NOT NULL DEFAULT 0 COMMENT 'TCC 冻结额度',
    updated_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB;

-- 2PC 参与者日志：Prepare 与业务扣减写在同一个（保持打开的）事务里。
-- 协调者宕机后，这里就是参与者恢复的依据（PREPARED 记录 = 有一个未决事务）。
CREATE TABLE IF NOT EXISTS txn_prepare_log (
    xid        VARCHAR(64) NOT NULL COMMENT '全局事务号，由协调者 A 生成',
    state      VARCHAR(16) NOT NULL COMMENT 'PREPARED / COMMITTED / ROLLED_BACK',
    product_id VARCHAR(32) NOT NULL,
    qty        INT         NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    decided_at DATETIME(3) NULL,
    PRIMARY KEY (xid)
) ENGINE = InnoDB;

-- TCC 分支事务日志：状态机的载体，空回滚/悬挂/幂等防护全靠它。
CREATE TABLE IF NOT EXISTS tcc_log (
    xid        VARCHAR(64) NOT NULL,
    branch     VARCHAR(32) NOT NULL COMMENT '本服务固定 inventory',
    state      VARCHAR(20) NOT NULL COMMENT 'TRIED / CONFIRMED / CANCELLED / CANCELLED_EMPTY(空回滚占位)',
    qty        INT         NOT NULL COMMENT '本次冻结的数量，Cancel 还原用',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (xid, branch)
) ENGINE = InnoDB;

-- 消息幂等表：Saga / 本地消息表方案的扣减以 msgId 去重，
-- 发送方重投同一条消息时这里保证「只扣一次」。
CREATE TABLE IF NOT EXISTS processed_msg (
    msg_id       VARCHAR(64) PRIMARY KEY,
    detail       VARCHAR(255) NOT NULL COMMENT '业务摘要，便于人工核对',
    processed_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB;

-- Seata AT 模式的分支回滚日志：分支事务提交前由数据源代理自动写入，
-- 全局回滚时用它反向补偿。表结构来自 Seata 官方 db 脚本（mysql 版）。
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
