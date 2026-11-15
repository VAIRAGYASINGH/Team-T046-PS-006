-- ============================================================================
-- V1__create_core_schema.sql
-- PS-006 UPI Payment Switch: Core Participant, Account, and Calendar Schema
-- ============================================================================

-- Sequence for deterministic 12-digit RRN generation
CREATE SEQUENCE rrn_seq
    START WITH 1
    INCREMENT BY 1
    MINVALUE 1
    MAXVALUE 999999999999
    CYCLE;

-- Users master table
CREATE TABLE users (
    user_id             VARCHAR(32) PRIMARY KEY,
    name                VARCHAR(128) NOT NULL,
    mobile              VARCHAR(20) NOT NULL,
    device_bound_at     TIMESTAMP WITHOUT TIME ZONE,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Merchants master table
CREATE TABLE merchants (
    merchant_id         VARCHAR(32) PRIMARY KEY,
    name                VARCHAR(128) NOT NULL,
    mcc                 VARCHAR(4) NOT NULL,
    verified            BOOLEAN NOT NULL DEFAULT FALSE,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Accounts table (supports both User and Merchant accounts)
CREATE TABLE accounts (
    account_id          BIGSERIAL PRIMARY KEY,
    account_number      VARCHAR(32) NOT NULL UNIQUE,
    ifsc                VARCHAR(11) NOT NULL,
    user_id             VARCHAR(32) REFERENCES users(user_id) ON DELETE RESTRICT,
    merchant_id         VARCHAR(32) REFERENCES merchants(merchant_id) ON DELETE RESTRICT,
    balance             NUMERIC(19, 2) NOT NULL DEFAULT 0.00,
    currency            VARCHAR(3) NOT NULL DEFAULT 'INR',
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_account_owner CHECK (
        (user_id IS NOT NULL AND merchant_id IS NULL) OR
        (user_id IS NULL AND merchant_id IS NOT NULL)
    ),
    CONSTRAINT chk_account_balance_non_negative CHECK (balance >= 0.00)
);

-- Virtual Payment Addresses (VPAs) table
CREATE TABLE vpas (
    vpa                 VARCHAR(128) PRIMARY KEY,
    account_id          BIGINT NOT NULL REFERENCES accounts(account_id) ON DELETE RESTRICT,
    owner_type          VARCHAR(16) NOT NULL CHECK (owner_type IN ('USER', 'MERCHANT')),
    owner_id            VARCHAR(32) NOT NULL,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    active              BOOLEAN NOT NULL DEFAULT TRUE
);

-- Official statutory bank holidays table
CREATE TABLE holidays (
    holiday_date        DATE PRIMARY KEY,
    name                VARCHAR(128) NOT NULL,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

