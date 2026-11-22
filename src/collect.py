"""UPI Collect (Pull Payment) Request Engine for PS-006 UPI Switch.

Enforces statutory rule BR-07 (PIN Threshold & Expiry Bounds) and lifecycle operations
COLLECT, COLLECT_APPROVE, and COLLECT_DECLINE with velocity isolation and centralized limit checks.
"""

from dataclasses import dataclass
from datetime import datetime, timedelta
from decimal import Decimal
from typing import Dict, List, Optional, Tuple, Union

from src.data import MasterData
from src.ledger import DoubleEntryLedger
from src.models import (
    CollectApproveCommand,
    CollectCommand,
    CollectDeclineCommand,
    CollectRequest,
    Merchant,
    Transaction,
    TransactionStatus,
    User,
)
from src.rrn_generator import RRNGenerator
from src.validation import ValidationEngine, ValidationResult


class CollectProcessingError(Exception):
    """Raised when an illegal operation occurs during collect request processing."""
    pass


@dataclass
class CollectResult:
    """Structured result returned by collect operations."""
    is_success: bool
    status: TransactionStatus
    transaction: Optional[Transaction] = None
    message: str = ""
    failure_reason: Optional[str] = None
    rule_id: Optional[str] = None


class CollectService:
    """Manages creation, approval, decline, and expiration of UPI pull payments."""

    def __init__(
        self,
        master_data: MasterData,
        ledger: DoubleEntryLedger,
        validation_engine: ValidationEngine,
        rrn_generator: RRNGenerator,
        transactions: Dict[str, Transaction],
        transaction_log: List[Transaction],
    ):
        self.master_data = master_data
        self.ledger = ledger
        self.validation_engine = validation_engine
        self.rrn_generator = rrn_generator
        self.transactions = transactions
        self.transaction_log = transaction_log
        self.collect_requests: Dict[str, CollectRequest] = {}

    def initiate_collect(self, command: CollectCommand) -> Transaction:
        """Create a new COLLECT request evaluating BR-07 PIN threshold and expiry rules."""
        rrn = self.rrn_generator.get_or_create_rrn(command.txn_id)

        requester: Optional[Union[User, Merchant]] = self.master_data.get_entity(command.requester_vpa.strip())
        payer: Optional[User] = self.master_data.users_by_vpa.get(command.payer_vpa.strip())

        val_result: ValidationResult = self.validation_engine.validate_collect(
            command=command,
            requester=requester,
            payer=payer,
            existing_transactions=self.transactions,
        )

        resolved_expiry_minutes = command.expiry_minutes if command.expiry_minutes > 0 else 30

        if not val_result.is_valid:
            failed_txn = Transaction(
                txn_id=command.txn_id,
                command=command.command_name,
                timestamp=command.timestamp,
                payer_vpa=command.payer_vpa,
                payee_vpa=command.requester_vpa,
                amount=command.amount,
                status=TransactionStatus.FAILED,
                rrn=rrn,
                line_no=command.line_no,
                failure_reason=val_result.failure_reason,
                rule_ids=val_result.rule_id,
                is_p2m=val_result.is_p2m,
                merchant_verified=val_result.merchant_verified,
                expiry_minutes=command.expiry_minutes,
                pin_flag=command.pin_flag,
            )
            # Only store in transactions index if not a duplicate txn_id collision
            if val_result.rule_id != "DUPLICATE_TXN":
                self.transactions[command.txn_id] = failed_txn
            self.transaction_log.append(failed_txn)
            return failed_txn

        # Valid Collect Request:
        # Payer is NOT debited at initiation; no escrow movement occurs yet.
        expires_at = command.timestamp + timedelta(minutes=resolved_expiry_minutes)

        txn = Transaction(
            txn_id=command.txn_id,
            command=command.command_name,
            timestamp=command.timestamp,
            payer_vpa=command.payer_vpa,
            payee_vpa=command.requester_vpa,
            amount=command.amount,
            status=TransactionStatus.PENDING,
            rrn=rrn,
            line_no=command.line_no,
            is_p2m=val_result.is_p2m,
            merchant_verified=val_result.merchant_verified,
            expiry_minutes=resolved_expiry_minutes,
            pin_flag=command.pin_flag,
            expires_at=expires_at,
        )

        self.transactions[command.txn_id] = txn
        self.transaction_log.append(txn)

        collect_req = CollectRequest(
            txn_id=command.txn_id,
            timestamp=command.timestamp,
            requester_vpa=command.requester_vpa,
            payer_vpa=command.payer_vpa,
            amount=command.amount,
            expiry_minutes=resolved_expiry_minutes,
            pin_flag=command.pin_flag,
            expires_at=expires_at,
            status=TransactionStatus.PENDING,
            line_no=command.line_no,
        )
        self.collect_requests[command.txn_id] = collect_req

        return txn

    def approve_collect(self, command: CollectApproveCommand) -> Transaction:
        """Process payer authorization (COLLECT_APPROVE) with expiry and limit validation."""
        txn = self.transactions.get(command.txn_id)
        if txn is None:
            raise CollectProcessingError(f"Collect request '{command.txn_id}' not found for approval")

        # 1. Idempotency Check: Already approved
        if txn.status == TransactionStatus.SUCCESS:
            return txn

        # 2. State Invariants Check
        if txn.status == TransactionStatus.DECLINED:
            raise CollectProcessingError(
                f"Cannot approve collect request '{command.txn_id}' in status DECLINED"
            )
        if txn.status == TransactionStatus.EXPIRED:
            raise CollectProcessingError(
                f"Cannot approve collect request '{command.txn_id}' in status EXPIRED"
            )
        if txn.status != TransactionStatus.PENDING:
            raise CollectProcessingError(
                f"Cannot approve collect request '{command.txn_id}' with status '{txn.status}'; must be PENDING"
            )

        # 3. Expiry Check (Rule BR-07: t > t_expires)
        if txn.expires_at is not None and command.timestamp > txn.expires_at:
            txn.status = TransactionStatus.EXPIRED
            txn.failure_reason = "Collect request expired"
            txn.rule_ids = "BR-07"

            approve_log = Transaction(
                txn_id=txn.txn_id,
                command=command.command_name,
                timestamp=command.timestamp,
                payer_vpa=txn.payer_vpa,
                payee_vpa=txn.payee_vpa,
                amount=txn.amount,
                status=TransactionStatus.EXPIRED,
                rrn=self.rrn_generator.next_rrn(),
                line_no=command.line_no,
                rule_ids="BR-07",
                failure_reason="Collect request expired",
                is_p2m=txn.is_p2m,
                merchant_verified=txn.merchant_verified,
            )
            self.transaction_log.append(approve_log)
            return txn

        # 4. Centralized Limits Validation Pipeline
        payer = self.master_data.users_by_vpa.get(txn.payer_vpa)
        payee = self.master_data.get_entity(txn.payee_vpa)

        val_result = self.validation_engine.validate_collect_approve(
            txn=txn,
            approval_timestamp=command.timestamp,
            payer=payer,
            payee=payee,
        )

        if not val_result.is_valid:
            txn.status = TransactionStatus.FAILED
            txn.failure_reason = val_result.failure_reason
            txn.rule_ids = val_result.rule_id

            approve_log = Transaction(
                txn_id=txn.txn_id,
                command=command.command_name,
                timestamp=command.timestamp,
                payer_vpa=txn.payer_vpa,
                payee_vpa=txn.payee_vpa,
                amount=txn.amount,
                status=TransactionStatus.FAILED,
                rrn=self.rrn_generator.next_rrn(),
                line_no=command.line_no,
                rule_ids=val_result.rule_id,
                failure_reason=val_result.failure_reason,
                is_p2m=txn.is_p2m,
                merchant_verified=txn.merchant_verified,
            )
            self.transaction_log.append(approve_log)
            return txn

        # 5. Financial Execution on Successful Approval:
        # Atomic double-entry transfer: Debit payer, Credit payee
        self.ledger.record_direct_settlement(
            txn_id=txn.txn_id,
            timestamp=command.timestamp,
            payer=payer,
            payee=payee,
            amount=txn.amount,
        )

        # 6. Increment Velocity and Amount Counters
        payer.daily_debit_amount += txn.amount
        payer.daily_debit_count += 1
        payer.monthly_debit_amount += txn.amount

        # 7. Beneficiary Payment History Update
        self.validation_engine.beneficiary_tracker.record_payment(
            payer_vpa=payer.vpa,
            payee_vpa=payee.vpa,
            timestamp=command.timestamp,
        )

        # 8. State Transition to SUCCESS
        txn.status = TransactionStatus.SUCCESS
        txn.approved_at = command.timestamp

        if txn.txn_id in self.collect_requests:
            self.collect_requests[txn.txn_id].status = TransactionStatus.SUCCESS
            self.collect_requests[txn.txn_id].approved_at = command.timestamp

        approve_log = Transaction(
            txn_id=txn.txn_id,
            command=command.command_name,
            timestamp=command.timestamp,
            payer_vpa=txn.payer_vpa,
            payee_vpa=txn.payee_vpa,
            amount=txn.amount,
            status=TransactionStatus.SUCCESS,
            rrn=self.rrn_generator.next_rrn(),
            line_no=command.line_no,
            is_p2m=txn.is_p2m,
            merchant_verified=txn.merchant_verified,
        )
        self.transaction_log.append(approve_log)

        return txn

    def decline_collect(self, command: CollectDeclineCommand) -> Transaction:
        """Process payer rejection (COLLECT_DECLINE) with complete velocity isolation."""
        txn = self.transactions.get(command.txn_id)
        if txn is None:
            raise CollectProcessingError(f"Collect request '{command.txn_id}' not found for decline")

        # 1. Idempotency Check: Already declined
        if txn.status == TransactionStatus.DECLINED:
            return txn

        # 2. State Invariants Check
        if txn.status == TransactionStatus.SUCCESS:
            raise CollectProcessingError(
                f"Cannot decline collect request '{command.txn_id}' in status SUCCESS"
            )
        if txn.status == TransactionStatus.EXPIRED:
            raise CollectProcessingError(
                f"Cannot decline collect request '{command.txn_id}' in status EXPIRED"
            )
        if txn.status != TransactionStatus.PENDING:
            raise CollectProcessingError(
                f"Cannot decline collect request '{command.txn_id}' with status '{txn.status}'; must be PENDING"
            )

        # 3. State Transition to DECLINED:
        # Zero financial movement; zero ledger entries; zero counter impact
        txn.status = TransactionStatus.DECLINED
        txn.declined_at = command.timestamp

        if txn.txn_id in self.collect_requests:
            self.collect_requests[txn.txn_id].status = TransactionStatus.DECLINED
            self.collect_requests[txn.txn_id].declined_at = command.timestamp

        decline_log = Transaction(
            txn_id=txn.txn_id,
            command=command.command_name,
            timestamp=command.timestamp,
            payer_vpa=txn.payer_vpa,
            payee_vpa=txn.payee_vpa,
            amount=txn.amount,
            status=TransactionStatus.DECLINED,
            rrn=self.rrn_generator.next_rrn(),
            line_no=command.line_no,
            is_p2m=txn.is_p2m,
            merchant_verified=txn.merchant_verified,
        )
        self.transaction_log.append(decline_log)

        return txn
