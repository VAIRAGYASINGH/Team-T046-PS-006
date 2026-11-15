-- ============================================================================
-- V3__create_ledger_tables.sql
-- PS-006 UPI Payment Switch: Immutable Double-Entry Ledger and Velocity Counters
-- ============================================================================

-- Immutable append-only double-entry financial ledger
CREATE TABLE ledger_entries (
    ledger_entry_id             BIGSERIAL PRIMARY KEY,
    sequence_number             BIGINT NOT NULL UNIQUE,
    txn_id                      VARCHAR(64) NOT NULL,
    account_id                  BIGINT NOT NULL REFERENCES accounts(account_id) ON DELETE RESTRICT,
    vpa                         VARCHAR(128) NOT NULL,
    leg_type                    VARCHAR(32) NOT NULL CHECK (leg_type IN (
                                    'DEBIT', 'CREDIT', 'REVERSAL', 'REFUND', 
                                    'DISPUTE_DEBIT', 'DISPUTE_CREDIT'
                                )),
    debit_amount                NUMERIC(19, 2) CHECK (debit_amount IS NULL OR debit_amount > 0.00),
    credit_amount               NUMERIC(19, 2) CHECK (credit_amount IS NULL OR credit_amount > 0.00),
    balance_after               NUMERIC(19, 2) NOT NULL CHECK (balance_after >= 0.00),
    entry_timestamp             TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    reference_ledger_entry_id   BIGINT REFERENCES ledger_entries(ledger_entry_id),
    description                 VARCHAR(255),
    created_at                  TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_debit_xor_credit CHECK (
        (debit_amount IS NOT NULL AND credit_amount IS NULL) OR
        (debit_amount IS NULL AND credit_amount IS NOT NULL)
    )
);

-- User daily transaction and velocity counters
CREATE TABLE daily_counters (
    counter_id          BIGSERIAL PRIMARY KEY,
    user_id             VARCHAR(32) NOT NULL REFERENCES users(user_id) ON DELETE RESTRICT,
    counter_date        DATE NOT NULL,
    debit_count         INT NOT NULL DEFAULT 0 CHECK (debit_count >= 0),
    debit_amount        NUMERIC(19, 2) NOT NULL DEFAULT 0.00 CHECK (debit_amount >= 0.00),
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_daily_counter UNIQUE (user_id, counter_date)
);

-- User monthly transaction counters (informational tracking)
CREATE TABLE monthly_counters (
    monthly_counter_id  BIGSERIAL PRIMARY KEY,
    user_id             VARCHAR(32) NOT NULL REFERENCES users(user_id) ON DELETE RESTRICT,
    year_month          VARCHAR(7) NOT NULL, -- Format: YYYY-MM
    debit_count         INT NOT NULL DEFAULT 0 CHECK (debit_count >= 0),
    debit_amount        NUMERIC(19, 2) NOT NULL DEFAULT 0.00 CHECK (debit_amount >= 0.00),
    version             BIGINT NOT NULL DEFAULT 0,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_monthly_counter UNIQUE (user_id, year_month)
);

-- Payer-Beneficiary relationship payment history for BR-04B cooling
CREATE TABLE beneficiary_payment_history (
    history_id          BIGSERIAL PRIMARY KEY,
    payer_vpa           VARCHAR(128) NOT NULL,
    payee_vpa           VARCHAR(128) NOT NULL,
    first_payment_at    TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    payment_count       INT NOT NULL DEFAULT 1 CHECK (payment_count >= 1),
    last_payment_at     TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_payer_payee_history UNIQUE (payer_vpa, payee_vpa)
);

