CREATE TABLE loyalty_points (
    customer_id VARCHAR(64) NOT NULL PRIMARY KEY,
    points      INT         NOT NULL,
    updated_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
