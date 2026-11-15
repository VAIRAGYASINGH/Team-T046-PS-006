-- ============================================================================
-- V2__create_payment_tables.sql
-- PS-006 UPI Payment Switch: Payment, Collect, Refund, and Transaction Events
-- ============================================================================

-- Authoritative transactions table
CREATE TABLE transactions (
    txn_id              VARCHAR(64) PRIMARY KEY,
    rrn                 VARCHAR(12) NOT NULL UNIQUE,
    command             VARCHAR(32) NOT NULL,
    payer_vpa           VARCHAR(128) NOT NULL,
    payee_vpa           VARCHAR(128) NOT NULL,
    amount              NUMERIC(19, 2) NOT NULL CHECK (amount > 0.00),
    remarks             VARCHAR(255),
    transaction_type    VARCHAR(16) NOT NULL CHECK (transaction_type IN ('P2P', 'P2M')),
    status              VARCHAR(32) NOT NULL CHECK (status IN (
                            'PENDING', 'SUCCESS', 'FAILED', 'REVERSED', 
                            'DEEMED_SUCCESS', 'RECON_SHORTFALL', 'EXPIRED', 'DECLINED'
                        )),
    rule_ids            VARCHAR(64),
    refunded_amount     NUMERIC(19, 2) NOT NULL DEFAULT 0.00 CHECK (refunded_amount >= 0.00),
    initiated_at        TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    reversed_at         TIMESTAMP WITHOUT TIME ZONE,
    deemed_success_at   TIMESTAMP WITHOUT TIME ZONE,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_refund_not_exceed_amount CHECK (refunded_amount <= amount)
);

-- Collect (pull payment) requests table
CREATE TABLE collect_requests (
    txn_id              VARCHAR(64) PRIMARY KEY REFERENCES transactions(txn_id) ON DELETE RESTRICT,
    requester_vpa       VARCHAR(128) NOT NULL,
    payer_vpa           VARCHAR(128) NOT NULL,
    amount              NUMERIC(19, 2) NOT NULL CHECK (amount > 0.00),
    expiry_minutes      INT NOT NULL CHECK (expiry_minutes > 0),
    pin_flag            BOOLEAN NOT NULL,
    expires_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    status              VARCHAR(32) NOT NULL CHECK (status IN ('PENDING', 'SUCCESS', 'DECLINED', 'EXPIRED', 'FAILED')),
    approved_at         TIMESTAMP WITHOUT TIME ZONE,
    declined_at         TIMESTAMP WITHOUT TIME ZONE,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Merchant-initiated refunds table
CREATE TABLE refunds (
    refund_id           BIGSERIAL PRIMARY KEY,
    original_txn_id     VARCHAR(64) NOT NULL REFERENCES transactions(txn_id) ON DELETE RESTRICT,
    timestamp           TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    amount              NUMERIC(19, 2) NOT NULL CHECK (amount > 0.00),
    merchant_vpa        VARCHAR(128) NOT NULL,
    customer_vpa        VARCHAR(128) NOT NULL,
    status              VARCHAR(32) NOT NULL CHECK (status IN ('SUCCESS', 'FAILED')),
    rrn                 VARCHAR(12) NOT NULL,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Transaction lifecycle audit events log
CREATE TABLE transaction_events (
    event_id            BIGSERIAL PRIMARY KEY,
    txn_id              VARCHAR(64) NOT NULL REFERENCES transactions(txn_id) ON DELETE RESTRICT,
    event_type          VARCHAR(32) NOT NULL,
    timestamp           TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    previous_status     VARCHAR(32),
    new_status          VARCHAR(32) NOT NULL,
    details             TEXT,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

