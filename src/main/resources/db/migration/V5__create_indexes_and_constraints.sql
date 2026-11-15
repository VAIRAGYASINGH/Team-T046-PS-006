-- ============================================================================
-- V5__create_indexes_and_constraints.sql
-- PS-006 UPI Payment Switch: Indexes and Idempotency Constraints
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Performance Indexes for Query Optimization
-- ----------------------------------------------------------------------------

-- VPAs & Accounts lookup indexes
CREATE INDEX idx_vpas_account_id ON vpas (account_id);
CREATE INDEX idx_vpas_owner ON vpas (owner_id, owner_type);
CREATE INDEX idx_accounts_user_id ON accounts (user_id);
CREATE INDEX idx_accounts_merchant_id ON accounts (merchant_id);

-- Transactions querying and timeout sweeps
CREATE INDEX idx_transactions_status_initiated_at ON transactions (status, initiated_at);
CREATE INDEX idx_transactions_payer_vpa_initiated_at ON transactions (payer_vpa, initiated_at);
CREATE INDEX idx_transactions_payee_vpa_initiated_at ON transactions (payee_vpa, initiated_at);
CREATE INDEX idx_transactions_initiated_at ON transactions (initiated_at);

-- Collect requests lookup
CREATE INDEX idx_collect_requests_payer_status ON collect_requests (payer_vpa, status);
CREATE INDEX idx_collect_requests_expires_at ON collect_requests (expires_at) WHERE status = 'PENDING';

-- Refunds lookup
CREATE INDEX idx_refunds_original_txn_id ON refunds (original_txn_id);

-- Transaction events audit log
CREATE INDEX idx_transaction_events_txn_id_timestamp ON transaction_events (txn_id, timestamp);

-- Ledger statement generation (CRITICAL for STATEMENT command performance)
CREATE INDEX idx_ledger_entries_vpa_timestamp ON ledger_entries (vpa, entry_timestamp);
CREATE INDEX idx_ledger_entries_account_timestamp ON ledger_entries (account_id, entry_timestamp);
CREATE INDEX idx_ledger_entries_txn_id ON ledger_entries (txn_id);

-- Disputes and SLA deadlines
CREATE INDEX idx_disputes_txn_id ON disputes (txn_id);
CREATE INDEX idx_disputes_status_deadline ON disputes (status, deadline);
CREATE INDEX idx_dispute_events_dispute_id_timestamp ON dispute_events (dispute_id, timestamp);

-- Reconciliation records lookup
CREATE INDEX idx_reconciliation_records_txn_id ON reconciliation_records (txn_id);
CREATE INDEX idx_reconciliation_records_status ON reconciliation_records (resolution_status);

-- ----------------------------------------------------------------------------
-- 2. Idempotency & Financial Consistency Constraints
-- ----------------------------------------------------------------------------

-- Note: Financial leg idempotency is guarded by application-level state transition checks
-- and strictly monotonic ledger sequence numbers (sequence_number UNIQUE).
-- A compound constraint on (txn_id, leg_type, account_id) is deliberately avoided because
-- legitimate UPI workflows (such as late ACK DEEMED_SUCCESS following auto-reversal, or
-- multiple partial refunds) legitimately post a second leg with leg_type = 'DEBIT' or 'REFUND'.


