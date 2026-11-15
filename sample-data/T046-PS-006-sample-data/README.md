# PS-006 – UPI Payment Switch with Limits, Reversals and Chargebacks — Sample Data Pack

Prepared by 10 SECONDS for Team T046.

## Files

| File | What it holds | Row count |
|---|---|---|
| `users.csv` | Registered UPI users (12) with linked account and opening counters | 12 |
| `merchants.csv` | Merchant VPAs (4) | 4 |
| `holidays.csv` | Bank holidays used for working-day counts (weekends are also non-working) | 4 |
| `commands.txt` | Command file processed top to bottom (count excludes `#` comment lines) | 118 |

## Column reference

Conventions: dates `YYYY-MM-DD`; timestamps `YYYY-MM-DD HH:MM:SS` (24h, IST, no timezone suffix); money in Rs as plain decimals with at most 2 places (no commas, no symbol); percentages as plain numbers (12.5 = 12.5%); booleans `true`/`false`; IDs uppercase with a fixed prefix.

### `users.csv`

| Column | Type | Units / format | Allowed values | Notes |
|---|---|---|---|---|
| `user_id` | string | USR-nnn | — | — |
| `name` | string | — | — | — |
| `vpa` | string | handle@psp | — | Unique |
| `bank_account` | string | 12 digits | — | — |
| `ifsc` | string | — | — | — |
| `balance` | decimal | Rs | — | Opening balance at 2026-10-01 00:00:00 |
| `vpa_created_at` | timestamp | — | — | New-VPA cooling: Rs 5,000 per txn cap for 24 h from this instant |
| `device_bound_at` | timestamp | — | — | Informational |
| `mobile` | string | — | — | — |
| `counter_date` | date | — | — | The day the daily counters below refer to |
| `daily_debit_count` | integer | — | — | Successful + pending debits already made on counter_date before the file starts |
| `daily_debit_amount` | decimal | Rs | — | Amount already counted towards the Rs 1,00,000 daily cap on counter_date |
| `monthly_debit_amount` | decimal | Rs | — | Informational (no monthly cap rule in the brief) |

### `merchants.csv`

| Column | Type | Units / format | Allowed values | Notes |
|---|---|---|---|---|
| `merchant_id` | string | MER-nnn | — | — |
| `name` | string | — | — | — |
| `vpa` | string | — | — | — |
| `mcc` | string | 4 digits | — | — |
| `verified` | boolean | — | true / false | VERIFIED merchants get the Rs 2,00,000 P2M cap; others the P2P cap |
| `bank_account` | string | — | — | — |
| `balance` | decimal | Rs | — | — |

### `holidays.csv`

| Column | Type | Units / format | Allowed values | Notes |
|---|---|---|---|---|
| `date` | date | — | — | — |
| `name` | string | — | — | — |

## Command file format

`commands.txt` holds one command per line in the form `COMMAND_NAME|arg1|arg2|...`. Commands must be processed strictly in file order (the file is the event log). Lines starting with `#` are comments and blank lines are ignored.

`BENE_RESPONSE` simulates the beneficiary bank's reply to a PAY: ACK (credit confirmed), NACK (credit failed → FAILED, remitter refunded immediately) or NO_RESPONSE (stays PENDING). `TIMEOUT_JOB` runs the 30-minute auto-reversal at its timestamp. Payee VPAs are users or merchants; a VPA not in either file is unknown. The daily cap and velocity counters start from the values in users.csv for counter_date. Days for TCC/chargeback windows are calendar days counted from the transaction date (T+45 means transaction_date + 45 days).

| Command | Arguments (in order) | Notes |
|---|---|---|
| `PAY` | txn_id, timestamp, payer_vpa, payee_vpa, amount, remarks | Respond SUCCESS / FAILED / PENDING with RRN and rule ids |
| `BENE_RESPONSE` | txn_id, timestamp, ACK|NACK|NO_RESPONSE | — |
| `TIMEOUT_JOB` | timestamp | Reverse every PENDING PAY older than 30 minutes |
| `COLLECT` | txn_id, timestamp, requester_vpa, payer_vpa, amount, expiry_minutes, pin_flag | — |
| `COLLECT_APPROVE` | txn_id, timestamp | Debits the payer like a PAY (counts for velocity and caps) |
| `COLLECT_DECLINE` | txn_id, timestamp | — |
| `REFUND` | txn_id, timestamp, amount | Merchant-initiated partial/full refund of a SUCCESS P2M txn |
| `DISPUTE` | dispute_id, timestamp, txn_id, type, raised_by, amount | type TCC / CHARGEBACK; raised_by REMITTER / BENEFICIARY_BANK |
| `DISPUTE_ACTION` | dispute_id, timestamp, action | ACCEPT / REPRESENT / PRE_ARBITRATION / CLOSE |
| `STATEMENT` | vpa, from_date, to_date | Print every leg, reversal and dispute adjustment |

## Planted scenarios

Every edge case from the brief, and where it sits in this pack:

- **Payment that exactly reaches Rs 1,00,000 daily total at 23:59:59 followed by one at 00:00:00** — USR-001 starts 2026-10-01 with Rs 82,000 already counted; after the 09:10 PAY of 8,000 the PAY at line 7 (23:59:59, Rs 10,000) lands exactly on Rs 1,00,000 (allowed); line 9 at 00:00:00 next day is a fresh day; note line 6 (14:00, Rs 15,000) comes earlier in the file and must FAIL on the cap
- **Auto-reversal and late ACK arriving in the same minute** — line 33 PAY gets NO_RESPONSE; TIMEOUT_JOB at line 35 (10:30:00) auto-reverses; late ACK at line 36 (10:30:40, same minute) → DEEMED_SUCCESS with re-debit
- **COLLECT of Rs 2,000.00 exactly (PIN threshold boundary)** — line 49: COLLECT of exactly 2000.00 with pin_flag false (allowed, approved at line 50); line 51: 2000.01 without PIN flag → reject
- **Third payment to new beneficiary at the 24-hour boundary to the second** — USR-006 → USR-011 (new beneficiary): line 24 and line 26 capped at 5,000 each; line 28 7,000 within 24 h → FAILED; line 30 7,000 at exactly 24:00 h after the first → allowed
- **Chargeback raised on day 45 exactly vs day 46** — Transactions of 2026-10-02: chargeback at line 137 on 2026-11-16 (day 45, allowed) vs line 143 on 2026-11-17 (day 46, reject)
- **Representment on the 5th working day falling on a bank holiday from the holiday file** — Dispute raised Mon 2026-11-02 (line 113); counting 5 weekdays lands on Mon 2026-11-09 which is a holiday in holidays.csv, so the 5th working day is 2026-11-10 — REPRESENT at line 126 is in time; PRE_ARBITRATION at line 144 is day 7 after representment
- **Partial refund followed by chargeback for the full original amount** — line 70 Rs 1,000 P2M; REFUND of 300 at line 73; chargeback for 1,000 at line 80 must be rejected (max 700); line 81 for 700 is accepted
- **VPA created 23h59m before first PAY** — USR-003 VPA created 2026-10-03 10:01:00; PAY at line 17 (2026-10-04 10:00:00 = 23 h 59 m) for 6,000 → FAILED on cooling; line 18 5,000 ok; USR-004 at line 21 exactly 24 h after creation → cooling over

Extra traps (not in the brief, but realistic):

- line 5: USR-002 already has 20 successful debits today → 21st returns FAILED U30
- line 38 reversed by timeout; line 58: dispute on a reversed transaction → reject
- line 42 pending → reversed; line 45 spends the balance; late ACK at line 47 → RECON_SHORTFALL (balance cannot be re-debited)
- line 52: COLLECT expiry 4321 min (> 72 h) → reject; line 53 + line 54: approval 31 min after a 30-min collect → expired; line 56: declined collect does not count for velocity
- line 75: TCC on T+5 (allowed); line 78: TCC on T+6 (reject); line 77: chargeback while TCC open → reject
- line 68: no action within 5 working days → auto-ACCEPTED; REPRESENT at line 104 is illegal; line 93: REPRESENT after ACCEPT → illegal
- line 84: 1,50,000 to a VERIFIED merchant (ok); line 86: same to an unverified merchant (FAILED); line 88: P2P exactly 1,00,000 (ok); line 92: 1,00,000.01 (FAILED)
- line 95: 1,20,000 from a user with Rs 1,500 balance — cap rule id must be reported (balance is checked last)
- line 97: NACK → FAILED with immediate refund; line 102: re-used txn_id → reject; line 105: unknown payee VPA → FAILED
- line 115: a BENE_RESPONSE line placed before the PAY line it belongs to — process in file order (treat as unknown txn at that point)

## What is NOT in this pack

Expected outputs are not provided for this set. Validate your engine with your own test suite against the rules in your brief (BR-01 … BR-nn). A separate hidden input set with the same schema will be used at final evaluation; outputs are compared field-for-field, so follow the column formats above exactly in your own output.

## Output format expected at evaluation

`status` ∈ SUCCESS / FAILED / PENDING / REVERSED / DEEMED_SUCCESS / RECON_SHORTFALL / EXPIRED / DECLINED; `rrn` a 12-digit reference you generate deterministically (e.g. zero-padded sequence); `leg_type` ∈ DEBIT / CREDIT / REVERSAL / REFUND / DISPUTE_DEBIT / DISPUTE_CREDIT.

Produce these CSV files (header row, same formatting conventions as the inputs, one row per entity in the order stated). Column names must match exactly.

### `txn_log.csv`

One row per PAY / COLLECT / COLLECT_APPROVE / COLLECT_DECLINE / REFUND in file order.

Columns, in order: `line_no`, `txn_id`, `command`, `timestamp`, `payer_vpa`, `payee_vpa`, `amount`, `status`, `rrn`, `rule_ids`

### `txn_final.csv`

Final state of every transaction after the whole file, by txn_id.

Columns, in order: `txn_id`, `final_status`, `amount`, `refunded_amount`, `reversed_at`, `deemed_success_at`

### `dispute_log.csv`

One row per DISPUTE / DISPUTE_ACTION in file order.

Columns, in order: `line_no`, `dispute_id`, `txn_id`, `type`, `action`, `timestamp`, `result_state`, `deadline`, `rule_ids`

### `statements.csv`

One row per ledger leg per STATEMENT, in statement order then chronological.

Columns, in order: `vpa`, `timestamp`, `txn_id`, `leg_type`, `debit`, `credit`, `running_balance`
