-- 订单服务（A）的全部表。A 是协调者/发起方，这里没有库存/积分，
-- 只有订单簿记和本地消息表。

-- 订单表：status 记录每个方案下的生命周期
-- （PREPARING→CONFIRMED/CANCELLED/IN_DOUBT；TRYING→…；INIT→CONFIRMED/DEAD）
CREATE TABLE IF NOT EXISTS orders (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    xid         VARCHAR(64) NOT NULL COMMENT '全局事务号，各方案独立前缀：2pc-/tcc-/saga-/msg-',
    scheme      VARCHAR(16) NOT NULL COMMENT '2PC / TCC / SAGA / LOCALMSG / SEATA',
    customer_id VARCHAR(32) NOT NULL,
    product_id  VARCHAR(32) NOT NULL,
    qty         INT         NOT NULL COMMENT '购买数量（扣库存量）',
    points      INT         NOT NULL COMMENT '消耗积分',
    status      VARCHAR(20) NOT NULL,
    note        VARCHAR(255) NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_xid (xid)
) ENGINE = InnoDB;

-- 本地消息表：与订单写入同一本地事务，重投器（LocalMessageRelay）轮询发送。
-- 就是 Outbox 模式：把「跨服务通知」从同步调用变成「先落库、后重投」。
CREATE TABLE IF NOT EXISTS outbox_message (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    msg_id        VARCHAR(64) NOT NULL COMMENT '发送给 B/C 的幂等键',
    xid           VARCHAR(64) NOT NULL,
    customer_id   VARCHAR(32) NOT NULL,
    product_id    VARCHAR(32) NOT NULL,
    qty           INT         NOT NULL,
    points        INT         NOT NULL,
    state         VARCHAR(12) NOT NULL COMMENT 'PENDING / SENT / DEAD',
    retry_count   INT         NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次可投递时间（指数退避）',
    last_error    VARCHAR(255) NULL,
    created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    sent_at       DATETIME(3) NULL,
    UNIQUE KEY ux_msg_id (msg_id),
    KEY idx_state_retry (state, next_retry_at)
) ENGINE = InnoDB;

-- Seata AT 模式下订单分支的回滚日志（官方表结构），随 Seata 章节启用。
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
