"""Beneficiary Responses, Deterministic Timeouts, and Reversal Engine for PS-006 UPI Switch.

Enforces statutory rules BR-05 (Timeout Auto-Reversal) and BR-06 (Late Acknowledgment Handling).
"""

from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal
from typing import Dict, List, Optional, Tuple, Union

from src.data import MasterData
from src.ledger import DoubleEntryLedger
from src.models import (
    BeneResponseCommand,
    BeneResponseType,
    Merchant,
    ReconciliationRecord,
    TimeoutJobCommand,
    Transaction,
    TransactionStatus,
    User,
)
from src.validation import ValidationEngine


class StateMachineError(Exception):
    """Raised when an illegal transaction state transition is attempted."""
    pass


class TransactionStateMachine:
    """Explicit, centralized state machine enforcing strict lifecycle transitions."""

    LEGAL_TRANSITIONS: Dict[TransactionStatus, set[TransactionStatus]] = {
        TransactionStatus.PENDING: {
            TransactionStatus.SUCCESS,
            TransactionStatus.FAILED,
            TransactionStatus.REVERSED,
            TransactionStatus.EXPIRED,
            TransactionStatus.DECLINED,
        },
        TransactionStatus.REVERSED: {
            TransactionStatus.DEEMED_SUCCESS,
            TransactionStatus.RECON_SHORTFALL,
        },
        TransactionStatus.SUCCESS: set(),
        TransactionStatus.FAILED: set(),
        TransactionStatus.DEEMED_SUCCESS: set(),
        TransactionStatus.RECON_SHORTFALL: set(),
        TransactionStatus.EXPIRED: set(),
        TransactionStatus.DECLINED: set(),
    }

    @classmethod
    def can_transition(cls, current: TransactionStatus, target: TransactionStatus) -> bool:
        """Check if transitioning from current to target status is strictly allowed."""
        if current == target:
            return True  # Idempotent no-op
        return target in cls.LEGAL_TRANSITIONS.get(current, set())

    @classmethod
    def assert_transition(cls, txn_id: str, current: TransactionStatus, target: TransactionStatus) -> None:
        """Assert transition is valid, raising StateMachineError otherwise."""
        if not cls.can_transition(current, target):
            raise StateMachineError(
                f"Illegal state transition for txn '{txn_id}': cannot move from '{current}' to '{target}'"
            )


@dataclass
class ReversalResult:
    """Structured result returned by reversal operations."""
    is_success: bool
    action_taken: str
    transaction: Optional[Transaction] = None
    status: Optional[TransactionStatus] = None
    reconciliation_record: Optional[ReconciliationRecord] = None
    message: str = ""


class ReversalEngine:
    """Orchestrates beneficiary responses, 30-minute timeout auto-reversals, and late ACK reconciliation."""

    def __init__(
        self,
        master_data: MasterData,
        ledger: DoubleEntryLedger,
        validation_engine: ValidationEngine,
        transactions: Dict[str, Transaction],
    ):
        self.master_data = master_data
        self.ledger = ledger
        self.validation_engine = validation_engine
        self.transactions = transactions
        self.reconciliation_records: List[ReconciliationRecord] = []
        self.state_machine = TransactionStateMachine()

    def process_bene_response(self, command: BeneResponseCommand) -> ReversalResult:
        """Process external beneficiary bank response (ACK, NACK, NO_RESPONSE)."""
        txn = self.transactions.get(command.txn_id)

        # 1. Out-of-order or unknown transaction ID (e.g. Line 115 TXN-0038)
        if txn is None:
            rec = ReconciliationRecord(
                record_id=f"REC-{len(self.reconciliation_records)+1:04d}",
                txn_id=command.txn_id,
                timestamp=command.timestamp,
                incident_type="OUT_OF_ORDER_PACKET",
                amount=Decimal("0.00"),
                payer_balance=Decimal("0.00"),
                deficiency=Decimal("0.00"),
                details=f"BENE_RESPONSE received for uninitiated or non-existent transaction '{command.txn_id}'",
                status="PENDING",
                line_no=command.line_no,
            )
            self.reconciliation_records.append(rec)
            return ReversalResult(
                is_success=False,
                action_taken="IGNORED_OUT_OF_ORDER",
                reconciliation_record=rec,
                message=f"BENE_RESPONSE received before PAY for {command.txn_id}; incident recorded",
            )

        payer = self.master_data.users_by_vpa.get(txn.payer_vpa)
        payee = self.master_data.get_entity(txn.payee_vpa)

        # 2. Case: Transaction is in PENDING state
        if txn.status == TransactionStatus.PENDING:
            if command.response == BeneResponseType.ACK:
                self.state_machine.assert_transition(txn.txn_id, txn.status, TransactionStatus.SUCCESS)
                if payee is None:
                    raise StateMachineError(f"Payee '{txn.payee_vpa}' not found for settlement")
                self.ledger.record_settlement_credit(
                    txn_id=txn.txn_id,
                    timestamp=command.timestamp,
                    payee=payee,
                    amount=txn.amount,
                )
                txn.status = TransactionStatus.SUCCESS
                return ReversalResult(
                    is_success=True,
                    action_taken="SETTLED",
                    transaction=txn,
                    status=TransactionStatus.SUCCESS,
                    message=f"Transaction {txn.txn_id} successfully settled to {txn.payee_vpa}",
                )

            elif command.response == BeneResponseType.NACK:
                self.state_machine.assert_transition(txn.txn_id, txn.status, TransactionStatus.FAILED)
                if payer is None:
                    raise StateMachineError(f"Payer '{txn.payer_vpa}' not found for refund")
                # Refund payer from switch clearing
                self.ledger.record_reversal(
                    txn_id=txn.txn_id,
                    timestamp=command.timestamp,
                    payer=payer,
                    amount=txn.amount,
                )
                # Restore payer velocity counters
                if payer.counter_date == txn.timestamp.date():
                    payer.daily_debit_amount = max(Decimal("0.00"), payer.daily_debit_amount - txn.amount)
                    payer.daily_debit_count = max(0, payer.daily_debit_count - 1)
                payer.monthly_debit_amount = max(Decimal("0.00"), payer.monthly_debit_amount - txn.amount)
                self.validation_engine.beneficiary_tracker.decrement_payment(payer.vpa, txn.payee_vpa)

                txn.status = TransactionStatus.FAILED
                txn.failure_reason = "Beneficiary bank rejected payment (NACK)"
                return ReversalResult(
                    is_success=True,
                    action_taken="REFUNDED_NACK",
                    transaction=txn,
                    status=TransactionStatus.FAILED,
                    message=f"Transaction {txn.txn_id} rejected by beneficiary; payer refunded",
                )

            elif command.response == BeneResponseType.NO_RESPONSE:
                # Retains PENDING state, awaiting timeout sweep
                return ReversalResult(
                    is_success=True,
                    action_taken="NO_OP_PENDING",
                    transaction=txn,
                    status=TransactionStatus.PENDING,
                    message=f"Transaction {txn.txn_id} received NO_RESPONSE; retained in PENDING state",
                )

        # 3. Case: Transaction is in REVERSED state (BR-06 Late Acknowledgment)
        elif txn.status == TransactionStatus.REVERSED:
            if command.response == BeneResponseType.ACK:
                if payer is None or payee is None:
                    raise StateMachineError(f"Payer or payee entity not found for late ACK reconciliation")

                # Branch A: Payer has sufficient available balance -> DEEMED_SUCCESS
                if payer.balance >= txn.amount:
                    self.state_machine.assert_transition(
                        txn.txn_id, txn.status, TransactionStatus.DEEMED_SUCCESS
                    )
                    self.ledger.record_late_ack_deemed_success(
                        txn_id=txn.txn_id,
                        timestamp=command.timestamp,
                        payer=payer,
                        payee=payee,
                        amount=txn.amount,
                    )
                    # Re-apply velocity counters
                    self.validation_engine.check_and_reset_daily_counters(payer, command.timestamp.date())
                    payer.daily_debit_amount += txn.amount
                    payer.daily_debit_count += 1
                    payer.monthly_debit_amount += txn.amount
                    self.validation_engine.beneficiary_tracker.record_payment(
                        payer.vpa, txn.payee_vpa, command.timestamp
                    )

                    txn.status = TransactionStatus.DEEMED_SUCCESS
                    txn.deemed_success_at = command.timestamp
                    return ReversalResult(
                        is_success=True,
                        action_taken="DEEMED_SUCCESS",
                        transaction=txn,
                        status=TransactionStatus.DEEMED_SUCCESS,
                        message=f"Late ACK for {txn.txn_id}: payer re-debited, status updated to DEEMED_SUCCESS",
                    )

                # Branch B: Payer has insufficient balance -> RECON_SHORTFALL (anti-overdraft invariant)
                else:
                    self.state_machine.assert_transition(
                        txn.txn_id, txn.status, TransactionStatus.RECON_SHORTFALL
                    )
                    txn.status = TransactionStatus.RECON_SHORTFALL
                    shortfall = txn.amount - payer.balance

                    rec = ReconciliationRecord(
                        record_id=f"REC-{len(self.reconciliation_records)+1:04d}",
                        txn_id=txn.txn_id,
                        timestamp=command.timestamp,
                        incident_type="RECON_SHORTFALL",
                        amount=txn.amount,
                        payer_balance=payer.balance,
                        deficiency=shortfall,
                        details=(
                            f"Late ACK on reversed txn {txn.txn_id} failed re-debit due to insufficient payer "
                            f"balance ({payer.balance} < {txn.amount}). Exact shortfall amount: {shortfall}"
                        ),
                        status="PENDING",
                        line_no=command.line_no,
                    )
                    self.reconciliation_records.append(rec)
                    return ReversalResult(
                        is_success=True,
                        action_taken="RECON_SHORTFALL",
                        transaction=txn,
                        status=TransactionStatus.RECON_SHORTFALL,
                        reconciliation_record=rec,
                        message=f"Late ACK for {txn.txn_id}: insufficient payer balance; logged RECON_SHORTFALL",
                    )

            elif command.response == BeneResponseType.NACK:
                # Late NACK on already reversed transaction -> idempotent no-op
                return ReversalResult(
                    is_success=True,
                    action_taken="DUPLICATE_IGNORED",
                    transaction=txn,
                    status=TransactionStatus.REVERSED,
                    message="Late NACK on already reversed transaction ignored",
                )

            elif command.response == BeneResponseType.NO_RESPONSE:
                return ReversalResult(
                    is_success=True,
                    action_taken="DUPLICATE_IGNORED",
                    transaction=txn,
                    status=TransactionStatus.REVERSED,
                    message="NO_RESPONSE on already reversed transaction ignored",
                )

        # 4. Case: Transaction is in terminal SUCCESS state
        elif txn.status == TransactionStatus.SUCCESS:
            if command.response == BeneResponseType.ACK:
                # Idempotent duplicate ACK
                return ReversalResult(
                    is_success=True,
                    action_taken="DUPLICATE_IGNORED",
                    transaction=txn,
                    status=TransactionStatus.SUCCESS,
                    message=f"Duplicate ACK received for transaction {txn.txn_id}; ignored",
                )
            else:
                # Conflicting response (NACK on settled SUCCESS)
                rec = ReconciliationRecord(
                    record_id=f"REC-{len(self.reconciliation_records)+1:04d}",
                    txn_id=txn.txn_id,
                    timestamp=command.timestamp,
                    incident_type="CONFLICTING_PACKET",
                    amount=txn.amount,
                    payer_balance=payer.balance if payer else Decimal("0.00"),
                    deficiency=Decimal("0.00"),
                    details=f"Conflicting response {command.response} received for settled SUCCESS txn {txn.txn_id}",
                    status="PENDING",
                    line_no=command.line_no,
                )
                self.reconciliation_records.append(rec)
                return ReversalResult(
                    is_success=False,
                    action_taken="CONFLICT_BLOCKED",
                    transaction=txn,
                    status=TransactionStatus.SUCCESS,
                    reconciliation_record=rec,
                    message=f"Conflicting response {command.response} on settled transaction {txn.txn_id} blocked",
                )

        # 5. Case: Transaction is in terminal FAILED state
        elif txn.status == TransactionStatus.FAILED:
            if command.response == BeneResponseType.NACK:
                # Idempotent duplicate NACK
                return ReversalResult(
                    is_success=True,
                    action_taken="DUPLICATE_IGNORED",
                    transaction=txn,
                    status=TransactionStatus.FAILED,
                    message=f"Duplicate NACK received for transaction {txn.txn_id}; ignored",
                )
            else:
                # Conflicting ACK on terminal FAILED
                rec = ReconciliationRecord(
                    record_id=f"REC-{len(self.reconciliation_records)+1:04d}",
                    txn_id=txn.txn_id,
                    timestamp=command.timestamp,
                    incident_type="CONFLICTING_PACKET",
                    amount=txn.amount,
                    payer_balance=payer.balance if payer else Decimal("0.00"),
                    deficiency=Decimal("0.00"),
                    details=f"Conflicting response {command.response} received for terminal FAILED txn {txn.txn_id}",
                    status="PENDING",
                    line_no=command.line_no,
                )
                self.reconciliation_records.append(rec)
                return ReversalResult(
                    is_success=False,
                    action_taken="CONFLICT_BLOCKED",
                    transaction=txn,
                    status=TransactionStatus.FAILED,
                    reconciliation_record=rec,
                    message=f"Conflicting response {command.response} on terminal FAILED transaction {txn.txn_id} blocked",
                )

        # 6. Case: Transaction is in DEEMED_SUCCESS or RECON_SHORTFALL
        elif txn.status in (TransactionStatus.DEEMED_SUCCESS, TransactionStatus.RECON_SHORTFALL):
            if command.response == BeneResponseType.ACK:
                return ReversalResult(
                    is_success=True,
                    action_taken="DUPLICATE_IGNORED",
                    transaction=txn,
                    status=txn.status,
                    message=f"Duplicate late ACK on status '{txn.status}' ignored",
                )
            else:
                return ReversalResult(
                    is_success=False,
                    action_taken="CONFLICT_BLOCKED",
                    transaction=txn,
                    status=txn.status,
                    message=f"Conflicting response {command.response} on terminal {txn.status} blocked",
                )

        # Fallback for unexpected status
        return ReversalResult(
            is_success=False,
            action_taken="UNSUPPORTED_STATE",
            transaction=txn,
            status=txn.status,
            message=f"Cannot process beneficiary response on transaction with status '{txn.status}'",
        )

    def process_timeout_job(
        self, command: TimeoutJobCommand, timeout_window_seconds: int = 1800
    ) -> List[Transaction]:
        """Process 30-minute automated timeout sweep (BR-05) on pending transactions."""
        reversed_txns: List[Transaction] = []

        # Find candidates currently in PENDING state
        candidates = [
            txn for txn in self.transactions.values()
            if txn.status == TransactionStatus.PENDING
        ]

        for txn in candidates:
            elapsed_seconds = (command.timestamp - txn.timestamp).total_seconds()
            if elapsed_seconds >= timeout_window_seconds:
                payer = self.master_data.users_by_vpa.get(txn.payer_vpa)
                if payer is None:
                    raise StateMachineError(f"Payer '{txn.payer_vpa}' not found for timeout refund")

                self.state_machine.assert_transition(txn.txn_id, txn.status, TransactionStatus.REVERSED)

                # 1. Refund payer from switch clearing escrow
                self.ledger.record_reversal(
                    txn_id=txn.txn_id,
                    timestamp=command.timestamp,
                    payer=payer,
                    amount=txn.amount,
                )

                # 2. Restore payer velocity counters
                if payer.counter_date == txn.timestamp.date():
                    payer.daily_debit_amount = max(Decimal("0.00"), payer.daily_debit_amount - txn.amount)
                    payer.daily_debit_count = max(0, payer.daily_debit_count - 1)
                payer.monthly_debit_amount = max(Decimal("0.00"), payer.monthly_debit_amount - txn.amount)
                self.validation_engine.beneficiary_tracker.decrement_payment(payer.vpa, txn.payee_vpa)

                # 3. Transition status and set reversed_at
                txn.status = TransactionStatus.REVERSED
                txn.reversed_at = command.timestamp
                reversed_txns.append(txn)

        return reversed_txns
