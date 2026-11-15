-- ============================================================================
-- V4__create_dispute_tables.sql
-- PS-006 UPI Payment Switch: Dispute Management, SLA Tracking, and Reconciliation
-- ============================================================================

-- Disputes master table (TCC and Chargeback)
CREATE TABLE disputes (
    dispute_id          VARCHAR(64) PRIMARY KEY,
    txn_id              VARCHAR(64) NOT NULL REFERENCES transactions(txn_id) ON DELETE RESTRICT,
    type                VARCHAR(32) NOT NULL CHECK (type IN ('TCC', 'CHARGEBACK')),
    raised_by           VARCHAR(32) NOT NULL CHECK (raised_by IN ('REMITTER', 'BENEFICIARY_BANK')),
    amount              NUMERIC(19, 2) NOT NULL CHECK (amount > 0.00),
    lodged_at           TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    status              VARCHAR(32) NOT NULL CHECK (status IN (
                            'OPEN_TCC', 'OPEN_CHARGEBACK', 'REPRESENTED', 
                            'PRE_ARBITRATION', 'ACCEPTED', 'AUTO_ACCEPTED', 
                            'CLOSED', 'REJECT'
                        )),
    deadline            TIMESTAMP WITHOUT TIME ZONE,
    rule_ids            VARCHAR(64),
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Dispute lifecycle events log
CREATE TABLE dispute_events (
    event_id            BIGSERIAL PRIMARY KEY,
    dispute_id          VARCHAR(64) NOT NULL REFERENCES disputes(dispute_id) ON DELETE RESTRICT,
    action              VARCHAR(32) NOT NULL CHECK (action IN (
                            'LODGE', 'ACCEPT', 'REPRESENT', 'PRE_ARBITRATION', 'CLOSE', 'AUTO_ACCEPT'
                        )),
    timestamp           TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    previous_state      VARCHAR(32),
    new_state           VARCHAR(32) NOT NULL,
    deadline            TIMESTAMP WITHOUT TIME ZONE,
    rule_ids            VARCHAR(64),
    remarks             VARCHAR(255),
    created_at          TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Inter-bank reconciliation anomalies and shortfall incident records
CREATE TABLE reconciliation_records (
    recon_id                BIGSERIAL PRIMARY KEY,
    txn_id                  VARCHAR(64) NOT NULL,
    incident_type           VARCHAR(32) NOT NULL CHECK (incident_type IN (
                                'RECON_SHORTFALL', 'OUT_OF_ORDER_PACKET', 'DUPLICATE_REPLAY'
                            )),
    recorded_at             TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    amount                  NUMERIC(19, 2) NOT NULL,
    payer_vpa               VARCHAR(128) NOT NULL,
    payee_vpa               VARCHAR(128) NOT NULL,
    payer_available_balance NUMERIC(19, 2) NOT NULL,
    resolution_status       VARCHAR(32) NOT NULL DEFAULT 'PENDING' CHECK (resolution_status IN (
                                'PENDING', 'RESOLVED', 'WRITTEN_OFF'
                            )),
    details                 TEXT,
    created_at              TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

