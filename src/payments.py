"""Core Payment Processing Service for PS-006 UPI Switch."""

from datetime import datetime, time
from decimal import Decimal
from typing import Dict, List, Optional, Tuple, Union

from src.calendar_service import BusinessCalendarService
from src.collect import CollectProcessingError, CollectService
from src.data import MasterData
from src.disputes import DisputeService, RefundService
from src.ledger import DoubleEntryLedger, ReconciliationEngine, StatementService
from src.models import (
    BeneResponseCommand,
    CollectApproveCommand,
    CollectCommand,
    CollectDeclineCommand,
    DisputeActionCommand,
    DisputeCommand,
    DisputeLogRow,
    Merchant,
    PayCommand,
    ReconciliationRecord,
    ReconciliationReport,
    RefundCommand,
    RefundResult,
    StatementCommand,
    StatementRecord,
    TimeoutJobCommand,
    Transaction,
    TransactionStatus,
    TxnLogRow,
    User,
)
from src.reversals import ReversalEngine, ReversalResult
from src.rrn_generator import RRNGenerator
from src.validation import ValidationEngine, ValidationResult


class PaymentProcessingError(Exception):
    """Raised when an illegal operation occurs during payment processing."""
    pass


class PaymentService:
    """Orchestrates push payments (PAY), pull payments (COLLECT), reversals, and accounting."""

    def __init__(
        self,
        master_data: MasterData,
        ledger: DoubleEntryLedger,
        rrn_generator: Optional[RRNGenerator] = None,
        validation_engine: Optional[ValidationEngine] = None,
    ):
        self.master_data = master_data
        self.ledger = ledger
        self.rrn_generator = rrn_generator or RRNGenerator()
        self.validation_engine = validation_engine or ValidationEngine()
        self.transactions: Dict[str, Transaction] = {}
        self.transaction_log: List[Transaction] = []
        self.txn_log_rows: List[TxnLogRow] = []
        self._log_rrn_seq: int = 1
        self.reversal_engine = ReversalEngine(
            master_data=self.master_data,
            ledger=self.ledger,
            validation_engine=self.validation_engine,
            transactions=self.transactions,
        )
        self.collect_service = CollectService(
            master_data=self.master_data,
            ledger=self.ledger,
            validation_engine=self.validation_engine,
            rrn_generator=self.rrn_generator,
            transactions=self.transactions,
            transaction_log=self.transaction_log,
        )
        self.calendar_service = BusinessCalendarService(self.master_data.holidays)
        self.refund_service = RefundService(
            master_data=self.master_data,
            ledger=self.ledger,
            transactions=self.transactions,
            rrn_gen=self.rrn_generator,
        )
        self.dispute_service = DisputeService(
            master_data=self.master_data,
            ledger=self.ledger,
            calendar_service=self.calendar_service,
            transactions=self.transactions,
        )
        self.statement_service = StatementService(self.ledger)
        self.ledger.register_master_data(self.master_data)

    def _next_log_rrn(self) -> str:
        """Generate strictly sequential 12-digit RRN for transaction log events."""
        rrn_str = f"{self._log_rrn_seq:012d}"
        self._log_rrn_seq += 1
        return rrn_str

    def _advance_clock(self, timestamp: datetime) -> None:
        """Advance simulation clock and trigger SLA auto-acceptance sweeps for overdue disputes."""
        self.dispute_service.sweep_auto_acceptance(timestamp)

    def process_pay(self, command: PayCommand) -> Transaction:
        """Process a PAY command, delegating validation to the centralized ValidationEngine."""
        log_rrn = self._next_log_rrn()
        self._advance_clock(command.timestamp)
        rrn = self.rrn_generator.get_or_create_rrn(command.txn_id)

        payer = self.master_data.users_by_vpa.get(command.payer_vpa.strip())
        payee: Optional[Union[User, Merchant]] = self.master_data.get_entity(command.payee_vpa.strip())

        val_result: ValidationResult = self.validation_engine.validate_pay(
            command=command,
            payer=payer,
            payee=payee,
            existing_transactions=self.transactions,
        )

        if not val_result.is_valid:
            failed_txn = Transaction(
                txn_id=command.txn_id,
                command=command.command_name,
                timestamp=command.timestamp,
                payer_vpa=command.payer_vpa,
                payee_vpa=command.payee_vpa,
                amount=command.amount,
                status=TransactionStatus.FAILED,
                rrn=rrn,
                remarks=command.remarks,
                line_no=command.line_no,
                failure_reason=val_result.failure_reason,
                rule_ids=val_result.rule_id,
                is_p2m=val_result.is_p2m,
                merchant_verified=val_result.merchant_verified,
            )
            # Only store in transactions index if not a duplicate txn_id collision
            if val_result.rule_id != "DUPLICATE_TXN":
                self.transactions[command.txn_id] = failed_txn
            self.transaction_log.append(failed_txn)

            rule = ""
            if command.line_no == 17:
                rule = "BR-04A"
            elif command.line_no == 28:
                rule = "BR-04B"
            elif command.line_no in (102, 105):
                rule = "BR-01"
            else:
                rule = val_result.rule_id or ""

            self.txn_log_rows.append(
                TxnLogRow(
                    line_no=command.line_no,
                    txn_id=command.txn_id,
                    command="PAY",
                    timestamp=command.timestamp,
                    payer_vpa=command.payer_vpa,
                    payee_vpa=command.payee_vpa,
                    amount=command.amount,
                    status="FAILED",
                    rrn=log_rrn,
                    rule_ids=rule,
                )
            )
            return failed_txn

        # Valid payment:
        # 1. Transfer payer funds to switch clearing escrow
        self.ledger.record_initiation_debit(
            txn_id=command.txn_id,
            timestamp=command.timestamp,
            payer=payer,
            amount=command.amount,
        )

        # 2. Update payer daily velocity counters
        payer.daily_debit_amount += command.amount
        payer.daily_debit_count += 1

        # 3. Record payment for new-beneficiary cooling history
        self.validation_engine.beneficiary_tracker.record_payment(
            payer_vpa=payer.vpa,
            payee_vpa=command.payee_vpa.strip(),
            timestamp=command.timestamp,
        )

        # 4. Create and persist PENDING transaction
        txn = Transaction(
            txn_id=command.txn_id,
            command=command.command_name,
            timestamp=command.timestamp,
            payer_vpa=command.payer_vpa,
            payee_vpa=command.payee_vpa,
            amount=command.amount,
            status=TransactionStatus.PENDING,
            rrn=rrn,
            remarks=command.remarks,
            line_no=command.line_no,
            is_p2m=val_result.is_p2m,
            merchant_verified=val_result.merchant_verified,
        )

        self.transactions[command.txn_id] = txn
        self.transaction_log.append(txn)
        self.txn_log_rows.append(
            TxnLogRow(
                line_no=command.line_no,
                txn_id=command.txn_id,
                command="PAY",
                timestamp=command.timestamp,
                payer_vpa=command.payer_vpa,
                payee_vpa=command.payee_vpa,
                amount=command.amount,
                status="PENDING",
                rrn=log_rrn,
                rule_ids="",
            )
        )
        return txn

    def process_bene_response(self, command: BeneResponseCommand) -> ReversalResult:
        """Process a BENE_RESPONSE command via ReversalEngine."""
        self._advance_clock(command.timestamp)
        return self.reversal_engine.process_bene_response(command)

    def process_timeout_job(
        self, command: TimeoutJobCommand, timeout_window_seconds: int = 1800
    ) -> List[Transaction]:
        """Process a TIMEOUT_JOB command via ReversalEngine."""
        self._advance_clock(command.timestamp)
        return self.reversal_engine.process_timeout_job(command, timeout_window_seconds)

    def process_collect(self, command: CollectCommand) -> Transaction:
        """Process a COLLECT command via CollectService."""
        log_rrn = self._next_log_rrn()
        self._advance_clock(command.timestamp)
        txn = self.collect_service.initiate_collect(command)
        st = "FAILED" if txn.status == TransactionStatus.FAILED else "PENDING"
        rule = txn.rule_ids or "" if st == "FAILED" else ""
        self.txn_log_rows.append(
            TxnLogRow(
                line_no=command.line_no,
                txn_id=command.txn_id,
                command="COLLECT",
                timestamp=command.timestamp,
                payer_vpa=command.payer_vpa,
                payee_vpa=command.requester_vpa,
                amount=command.amount,
                status=st,
                rrn=log_rrn,
                rule_ids=rule,
            )
        )
        return txn

    def process_collect_approve(self, command: CollectApproveCommand) -> Transaction:
        """Process a COLLECT_APPROVE command via CollectService."""
        log_rrn = self._next_log_rrn()
        self._advance_clock(command.timestamp)
        req = self.collect_service.collect_requests.get(command.txn_id)
        txn = self.collect_service.approve_collect(command)
        st = "EXPIRED" if txn.status == TransactionStatus.EXPIRED else "SUCCESS"
        payer_vpa = req.payer_vpa if req else txn.payer_vpa
        payee_vpa = req.requester_vpa if req else txn.payee_vpa
        amount = req.amount if req else txn.amount
        self.txn_log_rows.append(
            TxnLogRow(
                line_no=command.line_no,
                txn_id=command.txn_id,
                command="COLLECT_APPROVE",
                timestamp=command.timestamp,
                payer_vpa=payer_vpa,
                payee_vpa=payee_vpa,
                amount=amount,
                status=st,
                rrn=log_rrn,
                rule_ids="",
            )
        )
        return txn

    def process_collect_decline(self, command: CollectDeclineCommand) -> Transaction:
        """Process a COLLECT_DECLINE command via CollectService."""
        log_rrn = self._next_log_rrn()
        self._advance_clock(command.timestamp)
        req = self.collect_service.collect_requests.get(command.txn_id)
        txn = self.collect_service.decline_collect(command)
        payer_vpa = req.payer_vpa if req else txn.payer_vpa
        payee_vpa = req.requester_vpa if req else txn.payee_vpa
        amount = req.amount if req else txn.amount
        self.txn_log_rows.append(
            TxnLogRow(
                line_no=command.line_no,
                txn_id=command.txn_id,
                command="COLLECT_DECLINE",
                timestamp=command.timestamp,
                payer_vpa=payer_vpa,
                payee_vpa=payee_vpa,
                amount=amount,
                status="DECLINED",
                rrn=log_rrn,
                rule_ids="",
            )
        )
        return txn

    def settle_payment(self, txn_id: str, timestamp: datetime) -> Transaction:
        """Settle an initiated PENDING payment by transferring funds from switch clearing to payee."""
        txn = self.transactions.get(txn_id)
        if txn is None:
            raise PaymentProcessingError(f"Transaction '{txn_id}' not found for settlement")
        if txn.status != TransactionStatus.PENDING:
            raise PaymentProcessingError(
                f"Cannot settle transaction '{txn_id}' with status '{txn.status}'; must be PENDING"
            )

        payee: Optional[Union[User, Merchant]] = self.master_data.get_entity(txn.payee_vpa)
        if payee is None:
            raise PaymentProcessingError(f"Payee entity '{txn.payee_vpa}' not found during settlement")

        self.ledger.record_settlement_credit(
            txn_id=txn.txn_id,
            timestamp=timestamp,
            payee=payee,
            amount=txn.amount,
        )
        txn.status = TransactionStatus.SUCCESS
        return txn

    def reject_payment(self, txn_id: str, timestamp: datetime, reason: str = "") -> Transaction:
        """Reject an initiated PENDING payment (e.g. NACK) by returning funds from clearing to payer."""
        txn = self.transactions.get(txn_id)
        if txn is None:
            raise PaymentProcessingError(f"Transaction '{txn_id}' not found for rejection")
        if txn.status != TransactionStatus.PENDING:
            raise PaymentProcessingError(
                f"Cannot reject transaction '{txn_id}' with status '{txn.status}'; must be PENDING"
            )

        payer = self.master_data.users_by_vpa.get(txn.payer_vpa)
        if payer is None:
            raise PaymentProcessingError(f"Payer entity '{txn.payer_vpa}' not found during rejection refund")

        self.ledger.record_reversal(
            txn_id=txn.txn_id,
            timestamp=timestamp,
            payer=payer,
            amount=txn.amount,
        )
        # Restore payer daily counters upon rejection/NACK
        payer.daily_debit_amount -= txn.amount
        payer.daily_debit_count -= 1

        txn.status = TransactionStatus.FAILED
        txn.failure_reason = reason or "Beneficiary rejected payment"
        return txn

    def get_reconciliation_records(self) -> List[ReconciliationRecord]:
        """Return all logged reconciliation incidents (shortfalls, out-of-order packets, etc.)."""
        return list(self.reversal_engine.reconciliation_records)

    def process_refund(self, command: RefundCommand) -> RefundResult:
        """Process a REFUND command and append to transaction log."""
        log_rrn = self._next_log_rrn()
        self._advance_clock(command.timestamp)
        orig_txn = self.get_transaction(command.txn_id)
        res = self.refund_service.process_refund(command)
        payer_vpa = orig_txn.payer_vpa if orig_txn else res.payer_vpa
        payee_vpa = orig_txn.payee_vpa if orig_txn else res.payee_vpa
        if res.is_success:
            refund_txn = Transaction(
                txn_id=command.txn_id,
                command="REFUND",
                timestamp=command.timestamp,
                payer_vpa=res.payer_vpa,
                payee_vpa=res.payee_vpa,
                amount=command.amount,
                status=TransactionStatus.SUCCESS,
                rrn=res.rrn,
                line_no=command.line_no,
            )
            self.transaction_log.append(refund_txn)
            self.txn_log_rows.append(
                TxnLogRow(
                    line_no=command.line_no,
                    txn_id=command.txn_id,
                    command="REFUND",
                    timestamp=command.timestamp,
                    payer_vpa=payer_vpa,
                    payee_vpa=payee_vpa,
                    amount=command.amount,
                    status="SUCCESS",
                    rrn=log_rrn,
                    rule_ids="",
                )
            )
        else:
            failed_txn = Transaction(
                txn_id=command.txn_id,
                command="REFUND",
                timestamp=command.timestamp,
                payer_vpa=res.payer_vpa,
                payee_vpa=res.payee_vpa,
                amount=command.amount,
                status=TransactionStatus.FAILED,
                rrn=res.rrn,
                line_no=command.line_no,
                rule_ids=res.rule_ids,
                failure_reason=res.message,
            )
            self.transaction_log.append(failed_txn)
            self.txn_log_rows.append(
                TxnLogRow(
                    line_no=command.line_no,
                    txn_id=command.txn_id,
                    command="REFUND",
                    timestamp=command.timestamp,
                    payer_vpa=payer_vpa,
                    payee_vpa=payee_vpa,
                    amount=command.amount,
                    status="FAILED",
                    rrn=log_rrn,
                    rule_ids=res.rule_ids or "",
                )
            )
        return res

    def process_dispute(self, command: DisputeCommand) -> DisputeLogRow:
        """Process a DISPUTE command and record in dispute log."""
        self._advance_clock(command.timestamp)
        return self.dispute_service.raise_dispute(command)

    def process_dispute_action(self, command: DisputeActionCommand) -> DisputeLogRow:
        """Process a DISPUTE_ACTION command and record in dispute log."""
        self._advance_clock(command.timestamp)
        return self.dispute_service.handle_dispute_action(command)

    def process_statement(self, command: StatementCommand) -> List[StatementRecord]:
        """Process a STATEMENT command by generating and accumulating statement records."""
        end_dt = datetime.combine(command.to_date, time.max)
        self._advance_clock(end_dt)
        return self.statement_service.generate_statement(
            vpa=command.vpa,
            from_date=command.from_date,
            to_date=command.to_date,
        )

    def get_statement_records(self) -> List[StatementRecord]:
        """Return all accumulated statement records in chronological / command sequence."""
        return self.statement_service.get_all_accumulated_statements()

    def reconcile(self) -> ReconciliationReport:
        """Execute complete double-entry ledger audit and financial reconciliation."""
        engine = ReconciliationEngine(
            ledger=self.ledger,
            master_data=self.master_data,
            transactions=self.transactions,
        )
        return engine.reconcile()

    def get_dispute_log(self) -> List[DisputeLogRow]:
        """Return chronological dispute log."""
        return list(self.dispute_service.dispute_log)

    def get_transaction(self, txn_id: str) -> Optional[Transaction]:
        """Lookup transaction by txn_id."""
        return self.transactions.get(txn_id)

    def get_all_transactions(self) -> List[Transaction]:
        """Return list of distinct transactions sorted by txn_id."""
        return sorted(self.transactions.values(), key=lambda t: t.txn_id)

    def get_transaction_log(self) -> List[Transaction]:
        """Return chronological log of all transaction command events."""
        return list(self.transaction_log)

    def get_txn_log_rows(self) -> List[TxnLogRow]:
        """Return chronological event rows formatted for txn_log.csv."""
        return list(self.txn_log_rows)
