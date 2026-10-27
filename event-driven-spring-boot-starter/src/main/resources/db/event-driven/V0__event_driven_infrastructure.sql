CREATE TABLE IF NOT EXISTS outbox_event (
    id           VARCHAR(36)  NOT NULL PRIMARY KEY,
    event_id     VARCHAR(64)  NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    aggregate_id VARCHAR(64)  NOT NULL,
    payload      LONGTEXT     NOT NULL,
    occurred_on  DATETIME(6)  NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    delivered_at DATETIME(6)  NULL,
    created_at   DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY idx_outbox_status_occurred (status, occurred_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS processed_event (
    event_id     VARCHAR(64) NOT NULL PRIMARY KEY,
    processed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
