CREATE TABLE inventory_stock (
    product_id VARCHAR(64) NOT NULL PRIMARY KEY,
    available  INT         NOT NULL,
    reserved   INT         NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO inventory_stock (product_id, available, reserved)
VALUES ('P-1001', 100, 0), ('P-1002', 50, 0), ('P-1003', 20, 0);
