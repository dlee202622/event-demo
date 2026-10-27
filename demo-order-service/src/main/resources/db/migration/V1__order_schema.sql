CREATE TABLE orders (
    id           VARCHAR(32)   NOT NULL PRIMARY KEY,
    customer_id  VARCHAR(64)   NOT NULL,
    status       VARCHAR(16)   NOT NULL,
    total_amount DECIMAL(12,2) NOT NULL,
    currency     VARCHAR(8)    NOT NULL,
    payment_id   VARCHAR(64)   NULL,
    created_at   DATETIME(6)   NOT NULL,
    updated_at   DATETIME(6)   NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_items (
    id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    order_id     VARCHAR(32)   NOT NULL,
    line_no      INT           NOT NULL,
    product_id   VARCHAR(64)   NOT NULL,
    product_name VARCHAR(128)  NOT NULL,
    unit_price   DECIMAL(12,2) NOT NULL,
    currency     VARCHAR(8)    NOT NULL,
    quantity     INT           NOT NULL,
    UNIQUE KEY uk_order_items_line (order_id, line_no),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_view (
    order_id     VARCHAR(32)   NOT NULL PRIMARY KEY,
    customer_id  VARCHAR(64)   NOT NULL,
    status       VARCHAR(16)   NOT NULL,
    status_desc  VARCHAR(32)   NOT NULL,
    total_amount DECIMAL(12,2) NOT NULL,
    currency     VARCHAR(8)    NOT NULL,
    item_count   INT           NOT NULL,
    created_at   DATETIME(6)   NOT NULL,
    updated_at   DATETIME(6)   NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE domain_event (
    seq          BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id     VARCHAR(64)   NOT NULL,
    event_type   VARCHAR(64)   NOT NULL,
    aggregate_id VARCHAR(64)   NOT NULL,
    payload      LONGTEXT      NOT NULL,
    occurred_on  DATETIME(6)   NOT NULL,
    KEY idx_domain_event_aggregate (aggregate_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
