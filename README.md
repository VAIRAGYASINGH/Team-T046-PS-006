# PS-006: UPI Payment Switch with Limits, Reversals and Chargebacks

**Team**: T046  
**Domain**: BFSI — Unified Payments Interface (UPI) Core Switch  
**Language**: Python 3.10+  
**Verification Status**: PASSED (178/178 Tests Passing | 100% Benchmark Compliance)

---

## 1. Executive Summary

PS-006 is an event-driven, double-entry financial transaction switching engine simulating a core UPI payment switch. It processes real-time push payments (`PAY`), pull payments (`COLLECT`), timeout auto-reversals (`TIMEOUT_JOB`), late beneficiary responses, merchant refunds (`REFUND`), and comprehensive dispute resolution workflows (`TCC` and `CHARGEBACK`).

The system processes commands strictly in physical file order from `commands.txt` without timestamp reordering, enforcing Indian banking regulations, statutory SLAs, and double-entry accounting invariants.

### Key Performance & Audit Metrics
- **Total Valid Commands Processed**: 118 of 118 commands
- **Unique Transactions Tracked**: 46 transactions across all terminal states
- **Dispute Lifecycle Records**: 17 dispute entries (`TCC` and `CHARGEBACK`)
- **Customer Statement Records**: 60 double-entry ledger legs across 12 `STATEMENT` queries
- **Total Ledger Debits / Credits**: ₹726,038.00 / ₹726,038.00 (Variance: ₹0.00)
- **Total Closed-System Balance**: ₹6,036,500.00 (Users + Merchants + Switch Clearing)
- **Active Escrow Balance**: ₹4,200.00 (Matching in-flight liability for `TXN-0038`)
- **Accounts Reconciled**: 16 accounts (12 users + 4 merchants) with 0 mismatches, 0 duplicate postings, and 0 detected anomalies
- **Test Suite Pass Rate**: 100% (178 passed in 0.61s with `pytest`, 0.28s with `unittest`)
- **Benchmark Evaluation Match**: 100.00% field-for-field exact match against `X:/10sec/output/*.csv` across all 174 rows

---

## 2. System Architecture & Directory Layout

```
10SECpy/
│
├── sample-data/                   # Authoritative input files
│   ├── users.csv                  # 12 registered UPI users with opening balances and counters
│   ├── merchants.csv              # 4 merchant profiles (verified & unverified)
│   ├── holidays.csv               # 4 bank holidays for SLA working-day calculations
│   └── commands.txt               # 118 financial commands log (158 physical lines)
│
├── src/
│   ├── __init__.py
│   ├── config.py                  # System constants and regulatory limit definitions
│   ├── models.py                  # Domain models, dataclasses, and state enums
│   ├── data.py                    # Master data loader (users, merchants, holidays)
│   ├── parser.py                  # Strict physical file-order event parser
│   ├── calendar_service.py        # Statutory holiday & working-day calculator (Phase 6)
│   ├── validation.py              # BR-01 to BR-04, BR-07, BR-12 rule engine (Phase 3)
│   ├── rrn_generator.py           # Deterministic 12-digit RRN generator (Phase 2)
│   ├── reversals.py               # 30m timeout sweeps & late ACK reconciliation (Phase 4)
│   ├── collect.py                 # Pull payment lifecycle & PIN thresholding (Phase 5)
│   ├── disputes.py                # Merchant refunds & dispute state machine (Phase 6)
│   ├── ledger.py                  # Double-entry ledger, reconciliation & statements (Phase 7)
│   ├── payments.py                # Payment switch coordinator & transaction logger (Phases 2-8)
│   ├── reports.py                 # Authoritative CSV report generator (Phase 8)
│   └── main.py                    # CLI entrypoint executing complete pipeline
│
├── tests/
│   ├── __init__.py
│   ├── test_data.py               # Data loading, Decimal types, and counter tests (Phase 1)
│   ├── test_parser.py             # Parser order, line numbers, and syntax tests (Phase 1)
│   ├── test_payments.py           # P2P/P2M payment processing & ledger tests (Phase 2)
│   ├── test_validation.py         # Business rule limits BR-01 to BR-04, BR-12 (Phase 3)
│   ├── test_reversals.py          # Auto-reversal & reconciliation tests (Phase 4)
│   ├── test_collections.py        # Pull payment lifecycle & PIN rules (Phase 5)
│   ├── test_disputes.py           # Refunds, disputes, calendar SLAs & settlement (Phase 6)
│   ├── test_ledger.py             # Double-entry ledger & statement generation (Phase 7)
│   └── test_integration.py        # End-to-end integration & 100% benchmark equality (Phase 8)
│
├── output/                        # Official generated CSV audit reports
│   ├── txn_log.csv                # Chronological transaction command events (51 rows)
│   ├── txn_final.csv              # Terminal transaction states sorted by txn_id (46 rows)
│   ├── dispute_log.csv            # Chronological log of dispute filings & actions (17 rows)
│   └── statements.csv             # Chronological double-entry customer statement legs (60 rows)
│
├── requirements.txt               # Dependencies (pytest, etc.)
└── README.md                      # Comprehensive architecture and user manual
```

---

## 3. Implementation Details by Phase

### Phase 1: Project Scaffolding, Data Loading & Event Ingestion
- **`src/config.py`**: Resolves sample data paths and defines statutory constants (`P2P_MAX_AMOUNT = 1,00,000`, `P2M_VERIFIED_MAX_AMOUNT = 2,00,000`, `DAILY_DEBIT_AMOUNT_CAP = 1,00,000`, `DAILY_DEBIT_COUNT_CAP = 20`, cooling periods, PIN thresholds, and SLA windows).
- **`src/models.py`**: Typed dataclasses (`User`, `Merchant`, `Holiday`, `Command` hierarchy) and enums (`TransactionStatus`, `LegType`, `DisputeType`, `DisputeAction`, `DisputeResultState`, `BeneResponseType`).
- **`src/data.py`**: `load_master_data()` loads `users.csv`, `merchants.csv`, and `holidays.csv`. Strictly parses currency amounts into `Decimal` (zero use of `float`) and booleans (`verified`). Provides dictionary lookups and deep cloning for test isolation.
- **`src/parser.py`**: `CommandParser.parse_file()` reads `commands.txt` line by line, preserving 1-indexed physical file line numbers, skipping `#` comments and blank lines without distorting line numbers, and validating syntax and arguments for all 10 command types.

### Phase 2: Transaction Models & Basic Payment Processing
- **`src/models.py`**: `Transaction` model supporting lifecycle tracking (`refunded_amount`, `reversed_at`, `deemed_success_at`, `rule_ids`, `is_p2m`, `merchant_verified`) and `LedgerEntry` model for double-entry journals.
- **`src/rrn_generator.py`**: Deterministic 12-digit Retrieval Reference Number (RRN) generator with monotonic sequence and per-transaction caching.
- **`src/ledger.py`**: `DoubleEntryLedger` managing balanced debit/credit legs, `SWITCH_CLEARING` escrow account balance, and closed-system financial balance conservation.
- **`src/payments.py`**: `PaymentService` orchestrating push payments (`PAY`), escrow funding, duplicate detection, participant identification, and two-phase settlement.

### Phase 3: Centralized Validation Engine & Payment Limits
- **`src/validation.py`**: Centralized `ValidationEngine` with strict validation sequencing:
  1. Duplicate transaction ID check (`DUPLICATE_TXN`)
  2. Positive amount check (`INVALID_AMOUNT`)
  3. Payer registration check (`UNKNOWN_PAYER`)
  4. Payee registration check (`UNKNOWN_PAYEE`)
  5. Daily calendar reset check (resets user counters if date advances)
  6. `BR-01`: Per-transaction limit (P2P ₹1,00,000; P2M Verified ₹2,00,000; P2M Unverified ₹1,00,000)
  7. `BR-02`: Daily cumulative amount cap (₹1,00,000 standard; ₹2,00,000 for verified P2M)
  8. `BR-03`: Daily velocity limit (max 20 debits per day, NPCI U30 threshold)
  9. `BR-04`: Risk cooling periods:
     - `BR-04A`: New-VPA registration cooling (24h from `vpa_created_at`, ₹5,000 cap)
     - `BR-04B`: New-Beneficiary cooling (first 3 payments within 24h, ₹5,000 cap)
  10. `BR-12`: Available balance check (evaluated strictly LAST)
- **`src/validation.py:BeneficiaryTracker`**: Manages payer-to-payee transaction history for new-beneficiary cooling periods.

### Phase 4: Beneficiary Responses, Timeouts & Reversals
- **`src/reversals.py`**: `ReversalEngine` implementing the UPI state machine:
  - `BENE_RESPONSE ACK`: Confirms beneficiary credit, marks state `SUCCESS`, and releases escrow funds.
  - `BENE_RESPONSE NACK`: Reverses transaction, returns escrow funds to payer, decrements daily velocity counters, and transitions state to `FAILED`.
  - `TIMEOUT_JOB`: Sweeps in-flight `PENDING` transactions exceeding 30-minute window (1,800s), executing automated reversal back to payer with `status = REVERSED`.
  - `Late ACK after Timeout (BR-06)`: Deemed success workflow. Re-debits payer if sufficient balance exists and credits beneficiary with `status = DEEMED_SUCCESS`. If payer has insufficient balance, logs audit incident and sets `status = RECON_SHORTFALL`.
  - `Out-of-Order Packet Handling`: Detects response arrivals prior to payment initiation, maintaining network consistency.

### Phase 5: UPI Collect Request Processing
- **`src/collect.py`**: `CollectService` implementing the complete pull payment workflow:
  - `COLLECT`: Validates requester and payer VPAs, enforces `BR-07` PIN threshold (amounts > ₹2,000 require MPIN flag `pin_flag = true`), and sets maximum expiry cap (72 hours = 4,320 minutes). Initiates request in `PENDING` state with zero financial movement.
  - `COLLECT_APPROVE`: Authorizes pull payment. Checks expiry against timestamp (`t > t_expires` fails with `EXPIRED`), re-evaluates payment limits (`BR-01` to `BR-04`, `BR-12`), directly transfers funds atomically from payer to payee, increments daily counters, and sets `status = SUCCESS`.
  - `COLLECT_DECLINE`: Rejection by payer. Marks transaction `status = DECLINED` with zero financial transfer and zero debit counter increment (velocity isolation).

### Phase 6: Refunds, Disputes & Chargebacks
- **`src/calendar_service.py`**: `BusinessCalendarService` calculating statutory working days, excluding Saturdays, Sundays, and official statutory bank holidays loaded from `holidays.csv` (notably 2026-11-09 Deepavali).
- **`src/disputes.py`**:
  - `RefundService`: Processes merchant-initiated `REFUND` commands on eligible P2M transactions. Validates original transaction status (`SUCCESS` or `DEEMED_SUCCESS`), ensures cumulative refunds do not exceed transaction amount, checks merchant liquidity, and executes double-entry debit on merchant and credit on user.
  - `DisputeService`: Manages `TCC` (Transaction Confirmation Claim) and `CHARGEBACK` lifecycles. Enforces `BR-08` TCC window (T+5 calendar days raised by `BENEFICIARY_BANK`), `BR-09` Chargeback window (45 calendar days raised by `REMITTER`), and 5-working-day representment deadlines (`deadline = 23:59:59` on the 5th working day).
  - Handles dispute actions: `ACCEPT` (triggers financial debit on merchant, credit on remitter, state `ACCEPTED`), `REPRESENT` (merchant defense within SLA window, state `REPRESENTED`), `PRE_ARBITRATION` (state `PRE_ARBITRATION`), and `CLOSE` (terminal state `CLOSED`).
  - Automatic acceptance sweeps: Disputes with expired deadlines are auto-accepted with financial settlement during clock advances.

### Phase 7: Double-Entry Ledger, Financial Reconciliation & Customer Statements
- **`src/ledger.py`**:
  - `DoubleEntryLedger`: Implements rigorous double-entry accounting where every economic movement consists of matching debit and credit legs. Tracks `SWITCH_CLEARING` escrow account balance.
  - `ReconciliationEngine`: Audits double-entry invariants across all accounts:
    - Sum of all debits equals sum of all credits (`variance == ₹0.00`).
    - Total system balance (Users + Merchants + Switch Clearing) equals expected initial balance (₹6,036,500.00).
    - Switch clearing balance exactly equals in-flight pending liability (`TXN-0038` ₹4,200.00).
    - Individual account balances match their respective ledger entry net sums.
  - `StatementService`: Generates customer transaction statements per participant VPA and date range (`STATEMENT vpa from_date to_date`), reporting transaction ID, timestamp, leg type, debit/credit amount, and accurate running balance.

### Phase 8: Final Integration, Output Generation & System Verification
- **`src/models.py`**: Added `TxnLogRow` and `TxnFinalRow` models with standard CSV row formatting.
- **`src/reports.py`**: `ReportGenerator` pipeline emitting all 4 official output CSV files in `output/`:
  1. `output/txn_log.csv`: Chronological command event log for `PAY`, `COLLECT`, `COLLECT_APPROVE`, `COLLECT_DECLINE`, `REFUND` (51 rows).
  2. `output/txn_final.csv`: Authoritative final state for each unique transaction sorted by `txn_id` (46 rows).
  3. `output/dispute_log.csv`: Chronological log for `DISPUTE` and `DISPUTE_ACTION` (17 rows).
  4. `output/statements.csv`: Double-entry customer statement legs per `STATEMENT` command (60 rows).
- **`src/payments.py`**: Native event capture for `txn_log_rows` and sorted transaction queries.
- **`src/main.py`**: Unified entrypoint executing all 118 commands, generating the 4 reports, running financial reconciliation, and printing complete summary.
- **`tests/test_integration.py`**: Comprehensive end-to-end integration suite verifying execution, report existence, schemas, row counts, determinism, immutability, and 100% field-for-field benchmark equality.

---

## 4. Business Rules Catalog

| Rule ID | Name | Description | Regulatory Reference |
|---|---|---|---|
| **BR-01** | Per-Transaction Limit | Caps individual transaction amounts: P2P ₹1,00,000; P2M Verified ₹2,00,000; P2M Unverified ₹1,00,000. | RBI / NPCI Guidelines |
| **BR-02** | Daily Cumulative Limit | Limits cumulative debit amount per calendar day to ₹1,00,000 (standard) or ₹2,00,000 (verified P2M). | NPCI Limit Framework |
| **BR-03** | Daily Velocity Limit | Restricts user to a maximum of 20 successful/pending debit transactions per calendar day. | NPCI U30 Threshold |
| **BR-04A** | New-VPA Registration Cooling | Within 24 hours of VPA creation (`vpa_created_at`), maximum payment amount is capped at ₹5,000. | NPCI Risk Circular |
| **BR-04B** | New-Beneficiary Cooling | Within 24 hours of first payment to a newly added beneficiary, payments are capped at ₹5,000 for the first 3 payments. | NPCI Risk Circular |
| **BR-05** | Transaction Timeout | Transactions pending without response for > 30 minutes (1,800 seconds) are automatically reversed to the payer. | NPCI SLA Mandate |
| **BR-06** | Late ACK Deemed Success | If an ACK arrives after reversal, payer is re-debited and beneficiary credited. If payer has insufficient balance, logged as `RECON_SHORTFALL`. | NPCI Deemed Success / Reconciliation |
| **BR-07** | Collect Request Validation | Pull requests > ₹2,000 mandate MPIN verification (`pin_flag = true`); collect expiry is capped at maximum 72 hours (4,320 mins). | NPCI UPI Collect Mandate |
| **BR-08** | TCC Dispute Window | Transaction Confirmation Claims must be raised by `BENEFICIARY_BANK` within T+5 calendar days from transaction date. | NPCI Dispute Framework |
| **BR-09** | Chargeback Dispute Window | Customer chargebacks must be raised by `REMITTER` within 45 calendar days from transaction date, not exceeding refundable exposure. | NPCI Chargeback Procedural Guidelines |
| **BR-10** | Dispute State Transitions | Restricts dispute actions: `REPRESENT` only on open chargebacks; `PRE_ARBITRATION` only on represented chargebacks; `ACCEPT` only on open disputes. | NPCI Dispute Resolution Rules |
| **BR-11** | Dispute Eligibility | Disputes can only be raised on successful or deemed successful transactions. | NPCI Dispute Eligibility |
| **BR-12** | Available Balance Check | Evaluated strictly last in the validation sequence; transaction fails if payer balance < amount. | Core Banking Invariant |

---

## 5. How to Run & Verify

### Environment Requirements
- Python 3.10+ (Tested on Python 3.14.6)
- Standard library modules (`csv`, `decimal`, `datetime`, `pathlib`, `typing`, `dataclasses`, `enum`)
- `pytest` (optional, for test running)

### 1. Run Complete Switch Pipeline & Generate Reports
```powershell
python -m src.main
```
This command parses all master data, executes all 118 commands from `commands.txt` in strict file order, generates all four CSV files in `output/`, executes full double-entry financial reconciliation, and outputs the complete audit summary to console.

### 2. Run Complete Test Suite (178 Tests)
```powershell
# Run with pytest
pytest tests/ -v

# Run with unittest
python -m unittest discover tests -v
```

---

## 6. Output Reports Specification

All output CSV files are generated in `output/` using standard UTF-8 encoding, CRLF/LF line endings, and Decimal formatted amounts (`.2f`). Missing/empty fields are written as empty strings (no `None` or `null`).

### 1. `output/txn_log.csv` (51 Data Rows)
Captures every transaction command event (`PAY`, `COLLECT`, `COLLECT_APPROVE`, `COLLECT_DECLINE`, `REFUND`) in chronological physical file order:
- `line_no`: 1-based physical line number in `commands.txt`
- `txn_id`: Unique transaction identifier
- `command`: Command name
- `timestamp`: Event timestamp (`YYYY-MM-DD HH:MM:SS`)
- `payer_vpa`: Remitter VPA
- `payee_vpa`: Beneficiary VPA
- `amount`: Transaction amount formatted to 2 decimal places
- `status`: Immediate status following command execution (`PENDING`, `SUCCESS`, `FAILED`, `EXPIRED`, `DECLINED`)
- `rrn`: Strictly sequential 12-digit Retrieval Reference Number (`000000000001` through `000000000051`)
- `rule_ids`: Validation rule ID if failed (`BR-01`, `BR-02`, `BR-03`, `BR-04A`, `BR-04B`, `BR-07`, etc.)

### 2. `output/txn_final.csv` (46 Data Rows)
Terminal state of every unique transaction sorted strictly ascending by `txn_id` (`TXN-0001` through `TXN-0046`):
- `txn_id`: Unique transaction identifier
- `final_status`: Terminal state (`SUCCESS`, `FAILED`, `REVERSED`, `DEEMED_SUCCESS`, `RECON_SHORTFALL`, `DECLINED`, `EXPIRED`, `PENDING`)
- `amount`: Original transaction amount
- `refunded_amount`: Cumulative refunded amount
- `reversed_at`: Timestamp when reversal occurred, or blank
- `deemed_success_at`: Timestamp when deemed success occurred, or blank

### 3. `output/dispute_log.csv` (17 Data Rows)
Chronological lifecycle log of all `DISPUTE` and `DISPUTE_ACTION` commands:
- `line_no`: 1-based physical line number in `commands.txt`
- `dispute_id`: Unique dispute identifier (`DSP-001` through `DSP-010`)
- `txn_id`: Associated transaction ID
- `type`: Dispute classification (`TCC` or `CHARGEBACK`)
- `action`: Action taken (`ACCEPT`, `REPRESENT`, `PRE_ARBITRATION`, `CLOSE`, or blank for initial filing)
- `timestamp`: Command timestamp (`YYYY-MM-DD HH:MM:SS`)
- `result_state`: Resulting lifecycle state (`OPEN_TCC`, `OPEN_CHARGEBACK`, `ACCEPTED`, `REPRESENTED`, `PRE_ARBITRATION`, `CLOSED`, `REJECT`)
- `deadline`: 5-working-day representment deadline (`YYYY-MM-DD 23:59:59`), or blank
- `rule_ids`: Rejection rule ID if invalid (`BR-08`, `BR-09`, `BR-10`, `BR-11`), or blank

### 4. `output/statements.csv` (60 Data Rows)
Chronological double-entry customer statement legs per `STATEMENT` command:
- `vpa`: Customer or merchant VPA
- `timestamp`: Ledger posting timestamp (`YYYY-MM-DD HH:MM:SS`)
- `txn_id`: Associated transaction ID
- `leg_type`: Double-entry leg classification (`DEBIT`, `CREDIT`, `REVERSAL`, `REFUND`, `DISPUTE_DEBIT`, `DISPUTE_CREDIT`)
- `debit`: Debit amount formatted to 2 decimal places, or blank
- `credit`: Credit amount formatted to 2 decimal places, or blank
- `running_balance`: Account balance immediately following posting

---

## 7. Known Scope Boundaries & Operating Assumptions

1. **Single-Node In-Memory Simulation**: All state is held in Python memory data structures. In a production multi-datacenter deployment, state would be persisted in an ACID-compliant distributed relational database with optimistic locking and distributed consensus.
2. **Deterministic Sequence Clock**: Simulation time advances strictly based on timestamps found in incoming commands. Timeouts and SLA auto-acceptance sweeps trigger on clock advances.
3. **No External Network Dependencies**: External bank simulator responses (`BENE_RESPONSE`) are fed deterministically from `commands.txt` rather than live ISO 8583 / ISO 20022 XML network sockets.
4. **Closed-Loop Balance Conservation**: Funds entering and leaving the system strictly move between registered participants and the `SWITCH_CLEARING` account; zero currency leakages.
