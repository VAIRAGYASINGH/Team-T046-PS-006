# PS-006: UPI Payment Switch with Limits, Reversals and Chargebacks

**Team**: T046    
**Language**: Python 3.10+  

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
│   ├── calendar_service.py        # Statutory holiday & working-day calculator 
│   ├── validation.py              # BR-01 to BR-04, BR-07, BR-12 rule engine 
│   ├── rrn_generator.py           # Deterministic 12-digit RRN generator 
│   ├── reversals.py               # 30m timeout sweeps & late ACK reconciliation 
│   ├── collect.py                 # Pull payment lifecycle & PIN thresholding 
│   ├── disputes.py                # Merchant refunds & dispute state machine 
│   ├── ledger.py                  # Double-entry ledger, reconciliation & statements 
│   ├── payments.py                # Payment switch coordinator & transaction logger 
│   ├── reports.py                 # Authoritative CSV report generator 
│   └── main.py                    # CLI entrypoint executing complete pipeline
│
├── tests/
│   ├── __init__.py
│   ├── test_data.py               # Data loading, Decimal types, and counter tests 
│   ├── test_parser.py             # Parser order, line numbers, and syntax tests 
│   ├── test_payments.py           # P2P/P2M payment processing & ledger tests 
│   ├── test_validation.py         # Business rule limits BR-01 to BR-04, BR-12 
│   ├── test_reversals.py          # Auto-reversal & reconciliation tests 
│   ├── test_collections.py        # Pull payment lifecycle & PIN rules 
│   ├── test_disputes.py           # Refunds, disputes, calendar SLAs & settlement 
│   ├── test_ledger.py             # Double-entry ledger & statement generation 
│   └── test_integration.py        # End-to-end integration & 100% benchmark equality 
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


## 3. Business Rules Catalog

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

## 4. How to Run & Verify

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

## 5. Output Reports Specification

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

## 6. Known Scope Boundaries & Operating Assumptions

1. **Single-Node In-Memory Simulation**: All state is held in Python memory data structures. In a production multi-datacenter deployment, state would be persisted in an ACID-compliant distributed relational database with optimistic locking and distributed consensus.
2. **Deterministic Sequence Clock**: Simulation time advances strictly based on timestamps found in incoming commands. Timeouts and SLA auto-acceptance sweeps trigger on clock advances.
3. **No External Network Dependencies**: External bank simulator responses (`BENE_RESPONSE`) are fed deterministically from `commands.txt` rather than live ISO 8583 / ISO 20022 XML network sockets.
4. **Closed-Loop Balance Conservation**: Funds entering and leaving the system strictly move between registered participants and the `SWITCH_CLEARING` account; zero currency leakages.
