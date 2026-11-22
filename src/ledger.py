"""Double-entry ledger and clearing/escrow accounting engine for PS-006 UPI Switch."""

import csv
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple, Union

from src.models import (
    LedgerEntry,
    LegType,
    Merchant,
    ReconciliationReport,
    StatementRecord,
    Transaction,
    TransactionStatus,
    User,
)

SWITCH_CLEARING_VPA = "SWITCH_CLEARING"


class LedgerAccountingError(Exception):
    """Raised when a double-entry balance or accounting invariant is breached."""
    pass


class DoubleEntryLedger:
    """Manages double-entry journal entries and switch clearing/escrow balance."""

    def __init__(self, initial_clearing_balance: Decimal = Decimal("0.00")):
        self._clearing_balance = initial_clearing_balance
        self._entries: List[LedgerEntry] = []
        self._entry_id_seq = 1
        self._initial_balances: Dict[str, Decimal] = {}

    @property
    def clearing_balance(self) -> Decimal:
        """Current balance held in the switch clearing/escrow account."""
        return self._clearing_balance

    def register_initial_balance(self, vpa: str, balance: Decimal) -> None:
        """Register the opening balance for an account."""
        self._initial_balances[vpa.strip()] = Decimal(str(balance))

    def register_master_data(self, master_data: Any) -> None:
        """Register opening balances for all users and merchants from master data."""
        for u in master_data.users_by_vpa.values():
            self.register_initial_balance(u.vpa, u.balance)
        for m in master_data.merchants_by_vpa.values():
            self.register_initial_balance(m.vpa, m.balance)
        self.register_initial_balance(SWITCH_CLEARING_VPA, Decimal("0.00"))

    def get_initial_balance(self, vpa: str) -> Optional[Decimal]:
        """Retrieve registered opening balance for a VPA."""
        return self._initial_balances.get(vpa.strip())

    def _next_entry_id(self) -> int:
        entry_id = self._entry_id_seq
        self._entry_id_seq += 1
        return entry_id

    def record_initiation_debit(
        self, txn_id: str, timestamp: datetime, payer: User, amount: Decimal
    ) -> Tuple[LedgerEntry, LedgerEntry]:
        """Record payer debit and corresponding switch clearing escrow credit upon payment initiation."""
        if amount <= Decimal("0.00"):
            raise LedgerAccountingError(f"Initiation amount must be positive, got {amount}")
        if payer.balance < amount:
            raise LedgerAccountingError(
                f"Payer {payer.vpa} balance ({payer.balance}) insufficient for amount {amount}"
            )

        # 1. Debit payer
        payer.balance -= amount
        debit_leg = LedgerEntry(
            entry_id=self._next_entry_id(),
            timestamp=timestamp,
            txn_id=txn_id,
            vpa=payer.vpa,
            leg_type=LegType.DEBIT,
            amount=amount,
            debit=amount,
            credit=None,
            running_balance=payer.balance,
        )

        # 2. Credit switch clearing escrow
        self._clearing_balance += amount
        credit_leg = LedgerEntry(
            entry_id=self._next_entry_id(),
            timestamp=timestamp,
            txn_id=txn_id,
            vpa=SWITCH_CLEARING_VPA,
            leg_type=LegType.CREDIT,
            amount=amount,
            debit=None,
            credit=amount,
            running_balance=self._clearing_balance,
        )

        self._entries.extend([debit_leg, credit_leg])
        return debit_leg, credit_leg

    def record_settlement_credit(
        self, txn_id: str, timestamp: datetime, payee: Union[User, Merchant], amount: Decimal
    ) -> Tuple[LedgerEntry, LedgerEntry]:
        """Record switch clearing escrow debit and beneficiary credit upon successful confirmation."""
        if amount <= Decimal("0.00"):
            raise LedgerAccountingError(f"Settlement amount must be positive, got {amount}")
        if self._clearing_balance < amount:
            raise LedgerAccountingError(
                f"Switch clearing balance ({self._clearing_balance}) insufficient for settlement of {amount}"
            )

        # 1. Debit switch clearing escrow
        self._clearing_balance -= amount
        debit_leg = LedgerEntry(
            entry_id=self._next_entry_id(),
            timestamp=timestamp,
            txn_id=txn_id,
            vpa=SWITCH_CLEARING_VPA,
            leg_type=LegType.DEBIT,
            amount=amount,
            debit=amount,
            credit=None,
            running_balance=self._clearing_balance,
        )

        # 2. Credit beneficiary
        payee.balance += amount
        credit_leg = LedgerEntry(
            entry_id=self._next_entry_id(),
            timestamp=timestamp,
            txn_id=txn_id,
            vpa=payee.vpa,
            leg_type=LegType.CREDIT,
            amount=amount,
            debit=None,
            credit=amount,
            running_balance=payee.balance,
        )

        self._entries.extend([debit_leg, credit_leg])
        return debit_leg, credit_leg

    def record_reversal(
        self, txn_id: str, timestamp: datetime, payer: User, amount: Decimal
    ) -> Tuple[LedgerEntry, LedgerEntry]:
        """Record switch clearing debit and payer credit for payment reversal or NACK refund."""
        if amount <= Decimal("0.00"):
            raise LedgerAccountingError(f"Reversal amount must be positive, got {amount}")
        if self._clearing_balance < amount:
            raise LedgerAccountingError(
                f"Switch clearing balance ({self._clearing_balance}) insufficient for reversal of {amount}"
            )

        # 1. Debit switch clearing escrow
        self._clearing_balance -= amount
        debit_leg = LedgerEntry(
            entry_id=self._next_entry_id(),
            timestamp=timestamp,
            txn_id=txn_id,
            vpa=SWITCH_CLEARING_VPA,
            leg_type=LegType.DEBIT,
            amount=amount,
            debit=amount,
            credit=None,
            running_balance=self._clearing_balance,
        )

        # 2. Credit payer (reversal)
        payer.balance += amount
        credit_leg = LedgerEntry(
            entry_id=self._next_entry_id(),
            timestamp=timestamp,
            txn_id=txn_id,
            vpa=payer.vpa,
            leg_type=LegType.REVERSAL,
            amount=amount,
            debit=None,
            credit=amount,
            running_balance=payer.balance,
        )

        self._entries.extend([debit_leg, credit_leg])
        return debit_leg, credit_leg

    def record_late_ack_deemed_success(
        self,
        txn_id: str,
        timestamp: datetime,
        payer: User,
        payee: Union[User, Merchant],
        amount: Decimal,
    ) -> Tuple[LedgerEntry, LedgerEntry]:
        """Record payer re-debit and payee credit for late ACK deemed success (BR-06)."""
        if amount <= Decimal("0.00"):
            raise LedgerAccountingError(f"Deemed success amount must be positive, got {amount}")
        if payer.balance < amount:
            raise LedgerAccountingError(
                f"Payer {payer.vpa} balance ({payer.balance}) insufficient for re-debit of {amount}"
            )

        # 1. Re-debit payer
        payer.balance -= amount
        debit_leg = LedgerEntry(
            entry_id=self._next_entry_id(),
            timestamp=timestamp,
            txn_id=txn_id,
            vpa=payer.vpa,
            leg_type=LegType.DEBIT,
            amount=amount,
            debit=amount,
            credit=None,
            running_balance=payer.balance,
        )

        # 2. Credit payee
        payee.balance += amount
        credit_leg = LedgerEntry(
            entry_id=self._next_entry_id(),
            timestamp=timestamp,
            txn_id=txn_id,
            vpa=payee.vpa,
            leg_type=LegType.CREDIT,
            amount=amount,
            debit=None,
            credit=amount,
            running_balance=payee.balance,
        )

        self._entries.extend([debit_leg, credit_leg])
        return debit_leg, credit_leg

    def record_direct_settlement(
        self,
        txn_id: str,
        timestamp: datetime,
        payer: User,
        payee: Union[User, Merchant],
        amount: Decimal,
    ) -> Tuple[LedgerEntry, LedgerEntry]:
        """Record direct debit on payer and credit on payee for approved pull payments (COLLECT_APPROVE)."""
        if amount <= Decimal("0.00"):
            raise LedgerAccountingError(f"Settlement amount must be positive, got {amount}")
        if payer.balance < amount:
            raise LedgerAccountingError(
                f"Payer {payer.vpa} balance ({payer.balance}) insufficient for amount {amount}"
            )

        # 1. Debit payer
        payer.balance -= amount
        debit_leg = LedgerEntry(
            entry_id=self._next_entry_id(),
            timestamp=timestamp,
            txn_id=txn_id,
            vpa=payer.vpa,
            leg_type=LegType.DEBIT,
            amount=amount,
            debit=amount,
            credit=None,
            running_balance=payer.balance,
        )

        # 2. Credit payee
        payee.balance += amount
        credit_leg = LedgerEntry(
            entry_id=self._next_entry_id(),
            timestamp=timestamp,
            txn_id=txn_id,
            vpa=payee.vpa,
            leg_type=LegType.CREDIT,
            amount=amount,
            debit=None,
            credit=amount,
            running_balance=payee.balance,
        )

        self._entries.extend([debit_leg, credit_leg])
        return debit_leg, credit_leg

    def record_refund(
        self,
        txn_id: str,
        timestamp: datetime,
        merchant: Merchant,
        user: User,
        amount: Decimal,
    ) -> Tuple[LedgerEntry, LedgerEntry]:
        """Record refund debit on merchant and credit on user (remitter)."""
        if amount <= Decimal("0.00"):
            raise LedgerAccountingError(f"Refund amount must be positive, got {amount}")
        if merchant.balance < amount:
            raise LedgerAccountingError(
                f"Merchant {merchant.vpa} balance ({merchant.balance}) insufficient for refund {amount}"
            )

        # 1. Debit merchant
        merchant.balance -= amount
        debit_leg = LedgerEntry(
            entry_id=self._next_entry_id(),
            timestamp=timestamp,
            txn_id=txn_id,
            vpa=merchant.vpa,
            leg_type=LegType.REFUND,
            amount=amount,
            debit=amount,
            credit=None,
            running_balance=merchant.balance,
        )

        # 2. Credit user
        user.balance += amount
        credit_leg = LedgerEntry(
            entry_id=self._next_entry_id(),
            timestamp=timestamp,
            txn_id=txn_id,
            vpa=user.vpa,
            leg_type=LegType.REFUND,
            amount=amount,
            debit=None,
            credit=amount,
            running_balance=user.balance,
        )

        self._entries.extend([debit_leg, credit_leg])
        return debit_leg, credit_leg

    def record_chargeback_settlement(
        self,
        txn_id: str,
        timestamp: datetime,
        payee: Union[User, Merchant],
        remitter: User,
        amount: Decimal,
    ) -> Tuple[LedgerEntry, LedgerEntry]:
        """Record dispute chargeback debit on payee and credit on remitter."""
        if amount <= Decimal("0.00"):
            raise LedgerAccountingError(f"Chargeback settlement amount must be positive, got {amount}")

        # 1. Debit payee
        payee.balance -= amount
        debit_leg = LedgerEntry(
            entry_id=self._next_entry_id(),
            timestamp=timestamp,
            txn_id=txn_id,
            vpa=payee.vpa,
            leg_type=LegType.DISPUTE_DEBIT,
            amount=amount,
            debit=amount,
            credit=None,
            running_balance=payee.balance,
        )

        # 2. Credit remitter
        remitter.balance += amount
        credit_leg = LedgerEntry(
            entry_id=self._next_entry_id(),
            timestamp=timestamp,
            txn_id=txn_id,
            vpa=remitter.vpa,
            leg_type=LegType.DISPUTE_CREDIT,
            amount=amount,
            debit=None,
            credit=amount,
            running_balance=remitter.balance,
        )

        self._entries.extend([debit_leg, credit_leg])
        return debit_leg, credit_leg

    def get_entries_for_vpa(self, vpa: str) -> List[LedgerEntry]:
        """Retrieve all ledger entries recorded for a specific VPA."""
        norm = vpa.strip()
        return [entry for entry in self._entries if entry.vpa == norm]

    def get_entries_for_txn(self, txn_id: str) -> List[LedgerEntry]:
        """Retrieve all ledger entries linked to a specific transaction ID."""
        return [entry for entry in self._entries if entry.txn_id == txn_id]

    def get_all_entries(self) -> List[LedgerEntry]:
        """Retrieve complete ledger journal."""
        return list(self._entries)

    def total_system_balance(
        self, users: List[User], merchants: List[Merchant]
    ) -> Decimal:
        """Compute the aggregate balance of all user accounts, merchant accounts, and switch clearing."""
        total_users = sum((u.balance for u in users), Decimal("0.00"))
        total_merchants = sum((m.balance for m in merchants), Decimal("0.00"))
        return total_users + total_merchants + self._clearing_balance

    def reset(self) -> None:
        """Reset ledger state for fresh simulation runs."""
        self._clearing_balance = Decimal("0.00")
        self._entries.clear()
        self._entry_id_seq = 1
        self._initial_balances.clear()


class StatementService:
    """Generates and accumulates account statement records based on double-entry ledger legs."""

    def __init__(self, ledger: DoubleEntryLedger):
        self.ledger = ledger
        self.accumulated_statements: List[StatementRecord] = []

    def generate_statement(
        self,
        vpa: str,
        from_date: Optional[date] = None,
        to_date: Optional[date] = None,
    ) -> List[StatementRecord]:
        """Extract and format chronological ledger legs for a VPA within a date window (inclusive)."""
        norm_vpa = vpa.strip()
        entries = self.ledger.get_entries_for_vpa(norm_vpa)

        filtered_entries: List[LedgerEntry] = []
        for e in entries:
            e_date = e.timestamp.date()
            if from_date is not None and e_date < from_date:
                continue
            if to_date is not None and e_date > to_date:
                continue
            filtered_entries.append(e)

        filtered_entries.sort(key=lambda e: (e.timestamp, e.entry_id))

        records: List[StatementRecord] = [
            StatementRecord(
                vpa=norm_vpa,
                timestamp=e.timestamp,
                txn_id=e.txn_id,
                leg_type=e.leg_type,
                debit=e.debit,
                credit=e.credit,
                running_balance=e.running_balance,
            )
            for e in filtered_entries
        ]

        self.accumulated_statements.extend(records)
        return records

    def get_all_accumulated_statements(self) -> List[StatementRecord]:
        """Return all accumulated statements in the order they were generated."""
        return list(self.accumulated_statements)

    def clear_accumulated_statements(self) -> None:
        """Clear accumulated statements cache."""
        self.accumulated_statements.clear()

    @staticmethod
    def export_statements_csv(
        records: List[StatementRecord],
        output_path: Union[str, Path],
    ) -> None:
        """Export statement records to CSV matching statements.csv schema."""
        path = Path(output_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, mode="w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(["vpa", "timestamp", "txn_id", "leg_type", "debit", "credit", "running_balance"])
            for rec in records:
                writer.writerow(rec.to_csv_row())


class ReconciliationEngine:
    """Audits double-entry ledger journals, account balances, and escrow liabilities."""

    def __init__(
        self,
        ledger: DoubleEntryLedger,
        master_data: Any,
        transactions: Dict[str, Transaction],
        initial_balances: Optional[Dict[str, Decimal]] = None,
    ):
        self.ledger = ledger
        self.master_data = master_data
        self.transactions = transactions
        self.initial_balances = initial_balances or {}

    def reconcile(self) -> ReconciliationReport:
        """Execute comprehensive financial reconciliation across all ledger entries and accounts."""
        entries = self.ledger.get_all_entries()
        anomalies: List[str] = []

        # 1. Total debits vs total credits across entire ledger
        total_debits = sum((e.debit for e in entries if e.debit is not None), Decimal("0.00"))
        total_credits = sum((e.credit for e in entries if e.credit is not None), Decimal("0.00"))
        debit_credit_variance = total_debits - total_credits
        if debit_credit_variance != Decimal("0.00"):
            anomalies.append(
                f"Debit/Credit mismatch: Debits={total_debits}, Credits={total_credits}, Variance={debit_credit_variance}"
            )

        # 2. System total balance conservation
        users = list(self.master_data.users_by_vpa.values())
        merchants = list(self.master_data.merchants_by_vpa.values())
        current_system_balance = self.ledger.total_system_balance(users, merchants)

        if self.initial_balances:
            expected_system_balance = sum(
                (bal for vpa, bal in self.initial_balances.items() if vpa != SWITCH_CLEARING_VPA),
                Decimal("0.00"),
            )
        else:
            init_user_bals = [
                self.ledger.get_initial_balance(u.vpa) if self.ledger.get_initial_balance(u.vpa) is not None else u.balance
                for u in users
            ]
            init_merch_bals = [
                self.ledger.get_initial_balance(m.vpa) if self.ledger.get_initial_balance(m.vpa) is not None else m.balance
                for m in merchants
            ]
            expected_system_balance = sum(init_user_bals, Decimal("0.00")) + sum(init_merch_bals, Decimal("0.00"))

        system_balance_variance = current_system_balance - expected_system_balance
        if system_balance_variance != Decimal("0.00"):
            anomalies.append(
                f"System balance not conserved: Current={current_system_balance}, Expected={expected_system_balance}, Variance={system_balance_variance}"
            )

        # 3. Clearing / Escrow Liability Agreement
        pending_escrow_liability = sum(
            (t.amount for t in self.transactions.values() if t.status == TransactionStatus.PENDING),
            Decimal("0.00"),
        )
        if self.ledger.clearing_balance != pending_escrow_liability:
            anomalies.append(
                f"Escrow mismatch: Clearing balance={self.ledger.clearing_balance}, Pending liability={pending_escrow_liability}"
            )

        # 4. Individual Account Reconciliations
        accounts_with_mismatch: List[str] = []
        all_vpas: Set[str] = set(self.master_data.users_by_vpa.keys()) | set(self.master_data.merchants_by_vpa.keys())
        total_accounts = len(all_vpas)

        for vpa in sorted(all_vpas):
            entity = self.master_data.get_entity(vpa)
            if entity is None:
                continue
            init_bal = self.initial_balances.get(vpa)
            if init_bal is None:
                init_bal = self.ledger.get_initial_balance(vpa)
            if init_bal is None:
                continue

            vpa_entries = self.ledger.get_entries_for_vpa(vpa)
            vpa_credits = sum((e.credit for e in vpa_entries if e.credit is not None), Decimal("0.00"))
            vpa_debits = sum((e.debit for e in vpa_entries if e.debit is not None), Decimal("0.00"))
            expected_bal = init_bal + vpa_credits - vpa_debits

            if entity.balance != expected_bal:
                accounts_with_mismatch.append(
                    f"{vpa}: Actual={entity.balance}, Expected={expected_bal} (Diff={entity.balance - expected_bal})"
                )

            if entity.balance < Decimal("0.00"):
                anomalies.append(f"Negative balance detected for {vpa}: {entity.balance}")

        # 5. Duplicate Postings Check
        duplicate_postings: List[str] = []
        entries_by_txn: Dict[str, List[LedgerEntry]] = {}
        for e in entries:
            entries_by_txn.setdefault(e.txn_id, []).append(e)

        for txn_id, txn_entries in entries_by_txn.items():
            init_debits = [e for e in txn_entries if e.leg_type == LegType.DEBIT and e.vpa != SWITCH_CLEARING_VPA]
            txn = self.transactions.get(txn_id)
            if txn and txn.status == TransactionStatus.DEEMED_SUCCESS:
                max_payer_debits = 2
            else:
                max_payer_debits = 1
            if len(init_debits) > max_payer_debits:
                duplicate_postings.append(f"Txn {txn_id} has {len(init_debits)} payer debits (max {max_payer_debits})")

            reversals = [e for e in txn_entries if e.leg_type == LegType.REVERSAL]
            if len(reversals) > 1:
                duplicate_postings.append(f"Txn {txn_id} has {len(reversals)} reversals")

        if duplicate_postings:
            anomalies.extend(duplicate_postings)

        # 6. Refund Exposure Checks
        for txn_id, txn in self.transactions.items():
            if txn.refunded_amount > txn.amount:
                anomalies.append(
                    f"Txn {txn_id} refund ({txn.refunded_amount}) exceeds original amount ({txn.amount})"
                )

        is_balanced = (
            debit_credit_variance == Decimal("0.00")
            and system_balance_variance == Decimal("0.00")
            and self.ledger.clearing_balance == pending_escrow_liability
            and len(accounts_with_mismatch) == 0
            and len(duplicate_postings) == 0
            and len(anomalies) == 0
        )

        return ReconciliationReport(
            is_balanced=is_balanced,
            total_debits=total_debits,
            total_credits=total_credits,
            debit_credit_variance=debit_credit_variance,
            total_system_balance=current_system_balance,
            expected_system_balance=expected_system_balance,
            system_balance_variance=system_balance_variance,
            clearing_balance=self.ledger.clearing_balance,
            pending_escrow_liability=pending_escrow_liability,
            total_accounts_reconciled=total_accounts,
            accounts_with_mismatch=accounts_with_mismatch,
            duplicate_postings=duplicate_postings,
            anomalies=anomalies,
        )
