CREATE TABLE IF NOT EXISTS invoice (
    id VARCHAR(64) PRIMARY KEY,
    customer_name VARCHAR(255) NOT NULL,
    total_cents BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_invoice_customer_name ON invoice(customer_name);
