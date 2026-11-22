"""Dispute management and resolution engine for PS-006 UPI Payment Switch.

Implements TCC and Chargeback workflows, statutory SLA tracking (BR-08, BR-09, BR-10, BR-11),
representment and pre-arbitration state transitions, automatic acceptance, and financial settlement.
"""

from datetime import datetime, time, timedelta
from decimal import Decimal
from typing import Dict, List, Optional, Union

from src.calendar_service import BusinessCalendarService
from src.data import MasterData
from src.ledger import DoubleEntryLedger
from src.models import (
    Dispute,
    DisputeAction,
    DisputeActionCommand,
    DisputeCommand,
    DisputeLogRow,
    DisputeResultState,
    DisputeType,
    Merchant,
    RefundCommand,
    RefundResult,
    Transaction,
    TransactionStatus,
    User,
)
from src.rrn_generator import RRNGenerator


class RefundService:
    """Handles merchant-initiated full and partial refunds on eligible P2M transactions."""

    def __init__(
        self,
        master_data: MasterData,
        ledger: DoubleEntryLedger,
        transactions: Dict[str, Transaction],
        rrn_gen: Optional[RRNGenerator] = None,
    ):
        self.master_data = master_data
        self.ledger = ledger
        self.transactions = transactions
        self.rrn_gen = rrn_gen or RRNGenerator()

    def process_refund(self, command: RefundCommand) -> RefundResult:
        """Process a merchant refund request strictly following PS-006 rules."""
        rrn = self.rrn_gen.next_rrn()
        amount = Decimal(str(command.amount))

        # 1. Lookup original transaction
        orig_txn = self.transactions.get(command.txn_id)
        if orig_txn is None or orig_txn.status not in (TransactionStatus.SUCCESS, TransactionStatus.DEEMED_SUCCESS):
            return RefundResult(
                is_success=False,
                line_no=command.line_no,
                txn_id=command.txn_id,
                timestamp=command.timestamp,
                payer_vpa="UNKNOWN",
                payee_vpa="UNKNOWN",
                amount=amount,
                status="FAILED",
                rrn=rrn,
                rule_ids="INVALID_ORIG_TXN",
                message=f"Original transaction '{command.txn_id}' not found or not in SUCCESS/DEEMED_SUCCESS status",
            )

        # 2. Amount positive check
        if amount <= Decimal("0.00"):
            return RefundResult(
                is_success=False,
                line_no=command.line_no,
                txn_id=command.txn_id,
                timestamp=command.timestamp,
                payer_vpa=orig_txn.payee_vpa,
                payee_vpa=orig_txn.payer_vpa,
                amount=amount,
                status="FAILED",
                rrn=rrn,
                rule_ids="INVALID_AMOUNT",
                message="Refund amount must be positive",
            )

        # 3. P2M Check: Merchant must be the payee
        merchant = self.master_data.merchants_by_vpa.get(orig_txn.payee_vpa)
        if merchant is None:
            return RefundResult(
                is_success=False,
                line_no=command.line_no,
                txn_id=command.txn_id,
                timestamp=command.timestamp,
                payer_vpa=orig_txn.payee_vpa,
                payee_vpa=orig_txn.payer_vpa,
                amount=amount,
                status="FAILED",
                rrn=rrn,
                rule_ids="NOT_P2M_TXN",
                message=f"Payee '{orig_txn.payee_vpa}' is not a registered merchant; refunds allowed only for P2M",
            )

        # 4. Cumulative refund amount cap check
        eligible_refund = orig_txn.amount - orig_txn.refunded_amount
        if amount > eligible_refund:
            return RefundResult(
                is_success=False,
                line_no=command.line_no,
                txn_id=command.txn_id,
                timestamp=command.timestamp,
                payer_vpa=orig_txn.payee_vpa,
                payee_vpa=orig_txn.payer_vpa,
                amount=amount,
                status="FAILED",
                rrn=rrn,
                rule_ids="REFUND_EXCEEDS_AMOUNT",
                message=f"Refund amount {amount} exceeds eligible refundable balance {eligible_refund}",
            )

        # 5. Merchant balance check
        if merchant.balance < amount:
            return RefundResult(
                is_success=False,
                line_no=command.line_no,
                txn_id=command.txn_id,
                timestamp=command.timestamp,
                payer_vpa=orig_txn.payee_vpa,
                payee_vpa=orig_txn.payer_vpa,
                amount=amount,
                status="FAILED",
                rrn=rrn,
                rule_ids="BR-12",
                message=f"Merchant {merchant.vpa} balance ({merchant.balance}) insufficient for refund {amount}",
            )

        # 6. Payer user lookup
        payer_user = self.master_data.users_by_vpa.get(orig_txn.payer_vpa)
        if payer_user is None:
            return RefundResult(
                is_success=False,
                line_no=command.line_no,
                txn_id=command.txn_id,
                timestamp=command.timestamp,
                payer_vpa=orig_txn.payee_vpa,
                payee_vpa=orig_txn.payer_vpa,
                amount=amount,
                status="FAILED",
                rrn=rrn,
                rule_ids="UNKNOWN_PAYER",
                message=f"Original payer user '{orig_txn.payer_vpa}' not found",
            )

        # 7. Execute double-entry accounting movement: debit merchant, credit user
        self.ledger.record_refund(
            txn_id=orig_txn.txn_id,
            timestamp=command.timestamp,
            merchant=merchant,
            user=payer_user,
            amount=amount,
        )

        # 8. Update original transaction state
        orig_txn.refunded_amount += amount

        return RefundResult(
            is_success=True,
            line_no=command.line_no,
            txn_id=orig_txn.txn_id,
            timestamp=command.timestamp,
            payer_vpa=merchant.vpa,
            payee_vpa=payer_user.vpa,
            amount=amount,
            status="SUCCESS",
            rrn=rrn,
            rule_ids=None,
            message="Refund processed successfully",
        )


class DisputeService:
    """Core dispute management and resolution engine adhering to PS-006 specifications,

    RBI/NPCI statutory SLA workflows (TCC, Chargeback, Pre-arbitration), and double-entry settlement.
    """

    def __init__(
        self,
        master_data: MasterData,
        ledger: DoubleEntryLedger,
        calendar_service: BusinessCalendarService,
        transactions: Dict[str, Transaction],
    ):
        self.master_data = master_data
        self.ledger = ledger
        self.calendar_service = calendar_service
        self.transactions = transactions
        self.disputes_by_id: Dict[str, Dispute] = {}
        self.disputes_by_txn_id: Dict[str, List[Dispute]] = {}
        self.dispute_log: List[DisputeLogRow] = []

    def check_and_apply_auto_acceptance(self, dispute: Dispute, current_timestamp: datetime) -> bool:
        """Check if open dispute has exceeded its 5-working-day SLA and auto-accept if overdue."""
        if (
            dispute.status in (DisputeResultState.OPEN_CHARGEBACK, DisputeResultState.OPEN_TCC)
            and dispute.deadline is not None
            and current_timestamp > dispute.deadline
        ):
            settle_timestamp = dispute.deadline
            dispute.status = DisputeResultState.AUTO_ACCEPTED
            dispute.deadline = None
            dispute.resolved_at = settle_timestamp

            # Settle chargeback financial adjustment
            if dispute.dispute_type == DisputeType.CHARGEBACK:
                self._settle_chargeback(dispute, settle_timestamp)
            return True
        return False

    def sweep_auto_acceptance(self, cutoff_timestamp: datetime) -> List[Dispute]:
        """Batch sweep all overdue open disputes up to cutoff timestamp."""
        swept: List[Dispute] = []
        for dispute in self.disputes_by_id.values():
            if (
                dispute.status in (DisputeResultState.OPEN_CHARGEBACK, DisputeResultState.OPEN_TCC)
                and dispute.deadline is not None
                and cutoff_timestamp > dispute.deadline
            ):
                if self.check_and_apply_auto_acceptance(dispute, cutoff_timestamp):
                    swept.append(dispute)
        return swept

    def _settle_chargeback(self, dispute: Dispute, timestamp: datetime) -> None:
        """Execute double-entry settlement for an accepted or auto-accepted chargeback claim."""
        txn = self.transactions.get(dispute.txn_id)
        if txn is None:
            return

        payee = self.master_data.get_entity(txn.payee_vpa)
        remitter = self.master_data.users_by_vpa.get(txn.payer_vpa)

        if payee is not None and remitter is not None:
            self.ledger.record_chargeback_settlement(
                txn_id=txn.txn_id,
                timestamp=timestamp,
                payee=payee,
                remitter=remitter,
                amount=dispute.amount,
            )

    def raise_dispute(
        self,
        command_or_line_no: Union[DisputeCommand, int] = 0,
        dispute_id: Optional[str] = None,
        timestamp: Optional[datetime] = None,
        txn_id: Optional[str] = None,
        dispute_type: Optional[Union[DisputeType, str]] = None,
        raised_by: Optional[str] = None,
        amount: Optional[Decimal] = None,
        line_no: Optional[int] = None,
    ) -> DisputeLogRow:
        """Lodge a new dispute (TCC or CHARGEBACK).

        Validates filing windows (T+5 for TCC, Day 45 for Chargeback),
        refundable exposure caps, mutual exclusivity with open TCC, and transaction eligibility.
        """
        if isinstance(command_or_line_no, DisputeCommand):
            actual_line_no = command_or_line_no.line_no
            dsp_id = command_or_line_no.dispute_id
            ts = command_or_line_no.timestamp
            target_txn_id = command_or_line_no.txn_id
            raw_dtype = command_or_line_no.dispute_type
            raw_raised_by = command_or_line_no.raised_by
            amt = command_or_line_no.amount
        else:
            actual_line_no = line_no if line_no is not None else (command_or_line_no if isinstance(command_or_line_no, int) else 0)
            dsp_id = dispute_id or ""
            ts = timestamp or datetime.now()
            target_txn_id = txn_id or ""
            raw_dtype = dispute_type or ""
            raw_raised_by = raised_by or ""
            amt = amount if amount is not None else Decimal("0.00")

        line_no = actual_line_no

        # Parse and normalize dispute type
        dtype_str = raw_dtype.value if hasattr(raw_dtype, "value") else str(raw_dtype).strip().upper()
        if dtype_str not in ("TCC", "CHARGEBACK"):
            row = DisputeLogRow(
                line_no=line_no,
                dispute_id=dsp_id,
                txn_id=target_txn_id,
                dispute_type=dtype_str,
                action="",
                timestamp=ts,
                result_state="REJECT",
                deadline=None,
                rule_ids="INVALID_DISPUTE_TYPE",
            )
            self.dispute_log.append(row)
            return row

        dtype = DisputeType(dtype_str)
        norm_raised_by = (raw_raised_by or "").strip().upper()
        std_amount = Decimal(str(amt))

        # 1. Idempotency & Duplicate Dispute ID Check
        if dsp_id in self.disputes_by_id:
            row = DisputeLogRow(
                line_no=line_no,
                dispute_id=dsp_id,
                txn_id=target_txn_id,
                dispute_type=dtype.value,
                action="",
                timestamp=ts,
                result_state="REJECT",
                deadline=None,
                rule_ids="DUPLICATE_DISPUTE_ID",
            )
            self.dispute_log.append(row)
            return row

        # 2. Transaction Lookup
        txn = self.transactions.get(target_txn_id)
        if txn is None:
            row = DisputeLogRow(
                line_no=line_no,
                dispute_id=dsp_id,
                txn_id=target_txn_id,
                dispute_type=dtype.value,
                action="",
                timestamp=ts,
                result_state="REJECT",
                deadline=None,
                rule_ids="INVALID_TXN",
            )
            self.dispute_log.append(row)
            return row

        # 3. Transaction Status Eligibility: Cannot dispute reversed or recon shortfall transactions (BR-11)
        if txn.status in (TransactionStatus.REVERSED, TransactionStatus.RECON_SHORTFALL):
            dispute = Dispute(
                dispute_id=dsp_id,
                txn_id=target_txn_id,
                dispute_type=dtype,
                raised_by=norm_raised_by,
                amount=std_amount,
                timestamp=ts,
                status=DisputeResultState.REJECT,
                deadline=None,
                rule_ids="BR-11",
                line_no=line_no,
            )
            self.disputes_by_id[dsp_id] = dispute
            row = DisputeLogRow(
                line_no=line_no,
                dispute_id=dsp_id,
                txn_id=target_txn_id,
                dispute_type=dtype.value,
                action="",
                timestamp=ts,
                result_state="REJECT",
                deadline=None,
                rule_ids="BR-11",
            )
            self.dispute_log.append(row)
            return row

        # 4. Transaction Status Eligibility: Only SUCCESS or DEEMED_SUCCESS debits are eligible
        if txn.status not in (TransactionStatus.SUCCESS, TransactionStatus.DEEMED_SUCCESS):
            dispute = Dispute(
                dispute_id=dsp_id,
                txn_id=target_txn_id,
                dispute_type=dtype,
                raised_by=norm_raised_by,
                amount=std_amount,
                timestamp=ts,
                status=DisputeResultState.REJECT,
                deadline=None,
                rule_ids="INELIGIBLE_TXN_STATUS",
                line_no=line_no,
            )
            self.disputes_by_id[dsp_id] = dispute
            row = DisputeLogRow(
                line_no=line_no,
                dispute_id=dsp_id,
                txn_id=target_txn_id,
                dispute_type=dtype.value,
                action="",
                timestamp=ts,
                result_state="REJECT",
                deadline=None,
                rule_ids="INELIGIBLE_TXN_STATUS",
            )
            self.dispute_log.append(row)
            return row

        existing_disputes = self.disputes_by_txn_id.get(target_txn_id, [])

        # 5. Open TCC Precedence: An open TCC strictly blocks a Chargeback (BR-11)
        if dtype == DisputeType.CHARGEBACK:
            has_open_tcc = any(
                d.dispute_type == DisputeType.TCC and d.status == DisputeResultState.OPEN_TCC
                for d in existing_disputes
            )
            if has_open_tcc:
                dispute = Dispute(
                    dispute_id=dsp_id,
                    txn_id=target_txn_id,
                    dispute_type=dtype,
                    raised_by=norm_raised_by,
                    amount=std_amount,
                    timestamp=ts,
                    status=DisputeResultState.REJECT,
                    deadline=None,
                    rule_ids="BR-11",
                    line_no=line_no,
                )
                self.disputes_by_id[dsp_id] = dispute
                row = DisputeLogRow(
                    line_no=line_no,
                    dispute_id=dsp_id,
                    txn_id=target_txn_id,
                    dispute_type=dtype.value,
                    action="",
                    timestamp=ts,
                    result_state="REJECT",
                    deadline=None,
                    rule_ids="BR-11",
                )
                self.dispute_log.append(row)
                return row

        # 6. TCC Window Validation: Within T+5 calendar days from transaction date (BR-08)
        if dtype == DisputeType.TCC:
            if norm_raised_by != "BENEFICIARY_BANK":
                dispute = Dispute(
                    dispute_id=dsp_id,
                    txn_id=target_txn_id,
                    dispute_type=dtype,
                    raised_by=norm_raised_by,
                    amount=std_amount,
                    timestamp=ts,
                    status=DisputeResultState.REJECT,
                    deadline=None,
                    rule_ids="BR-08",
                    line_no=line_no,
                )
                self.disputes_by_id[dsp_id] = dispute
                row = DisputeLogRow(
                    line_no=line_no,
                    dispute_id=dsp_id,
                    txn_id=target_txn_id,
                    dispute_type=dtype.value,
                    action="",
                    timestamp=ts,
                    result_state="REJECT",
                    deadline=None,
                    rule_ids="BR-08",
                )
                self.dispute_log.append(row)
                return row

            days_since_txn = (ts.date() - txn.timestamp.date()).days
            if days_since_txn > 5:
                dispute = Dispute(
                    dispute_id=dsp_id,
                    txn_id=target_txn_id,
                    dispute_type=dtype,
                    raised_by=norm_raised_by,
                    amount=std_amount,
                    timestamp=ts,
                    status=DisputeResultState.REJECT,
                    deadline=None,
                    rule_ids="BR-08",
                    line_no=line_no,
                )
                self.disputes_by_id[dsp_id] = dispute
                row = DisputeLogRow(
                    line_no=line_no,
                    dispute_id=dsp_id,
                    txn_id=target_txn_id,
                    dispute_type=dtype.value,
                    action="",
                    timestamp=ts,
                    result_state="REJECT",
                    deadline=None,
                    rule_ids="BR-08",
                )
                self.dispute_log.append(row)
                return row

            if std_amount <= Decimal("0.00") or std_amount > txn.amount:
                dispute = Dispute(
                    dispute_id=dsp_id,
                    txn_id=target_txn_id,
                    dispute_type=dtype,
                    raised_by=norm_raised_by,
                    amount=std_amount,
                    timestamp=ts,
                    status=DisputeResultState.REJECT,
                    deadline=None,
                    rule_ids="BR-08",
                    line_no=line_no,
                )
                self.disputes_by_id[dsp_id] = dispute
                row = DisputeLogRow(
                    line_no=line_no,
                    dispute_id=dsp_id,
                    txn_id=target_txn_id,
                    dispute_type=dtype.value,
                    action="",
                    timestamp=ts,
                    result_state="REJECT",
                    deadline=None,
                    rule_ids="BR-08",
                )
                self.dispute_log.append(row)
                return row

        # 7. Chargeback Window & Refundable Exposure Validation (BR-09)
        if dtype == DisputeType.CHARGEBACK:
            if norm_raised_by != "REMITTER":
                dispute = Dispute(
                    dispute_id=dsp_id,
                    txn_id=target_txn_id,
                    dispute_type=dtype,
                    raised_by=norm_raised_by,
                    amount=std_amount,
                    timestamp=ts,
                    status=DisputeResultState.REJECT,
                    deadline=None,
                    rule_ids="BR-09",
                    line_no=line_no,
                )
                self.disputes_by_id[dsp_id] = dispute
                row = DisputeLogRow(
                    line_no=line_no,
                    dispute_id=dsp_id,
                    txn_id=target_txn_id,
                    dispute_type=dtype.value,
                    action="",
                    timestamp=ts,
                    result_state="REJECT",
                    deadline=None,
                    rule_ids="BR-09",
                )
                self.dispute_log.append(row)
                return row

            days_since_txn = (ts.date() - txn.timestamp.date()).days
            if days_since_txn > 45:  # Day 45 inclusive, Day 46 rejected
                dispute = Dispute(
                    dispute_id=dsp_id,
                    txn_id=target_txn_id,
                    dispute_type=dtype,
                    raised_by=norm_raised_by,
                    amount=std_amount,
                    timestamp=ts,
                    status=DisputeResultState.REJECT,
                    deadline=None,
                    rule_ids="BR-09",
                    line_no=line_no,
                )
                self.disputes_by_id[dsp_id] = dispute
                row = DisputeLogRow(
                    line_no=line_no,
                    dispute_id=dsp_id,
                    txn_id=target_txn_id,
                    dispute_type=dtype.value,
                    action="",
                    timestamp=ts,
                    result_state="REJECT",
                    deadline=None,
                    rule_ids="BR-09",
                )
                self.dispute_log.append(row)
                return row

            if std_amount <= Decimal("0.00"):
                dispute = Dispute(
                    dispute_id=dsp_id,
                    txn_id=target_txn_id,
                    dispute_type=dtype,
                    raised_by=norm_raised_by,
                    amount=std_amount,
                    timestamp=ts,
                    status=DisputeResultState.REJECT,
                    deadline=None,
                    rule_ids="BR-09",
                    line_no=line_no,
                )
                self.disputes_by_id[dsp_id] = dispute
                row = DisputeLogRow(
                    line_no=line_no,
                    dispute_id=dsp_id,
                    txn_id=target_txn_id,
                    dispute_type=dtype.value,
                    action="",
                    timestamp=ts,
                    result_state="REJECT",
                    deadline=None,
                    rule_ids="BR-09",
                )
                self.dispute_log.append(row)
                return row

            total_refunded = txn.refunded_amount
            existing_active_cbs = sum(
                (d.amount for d in existing_disputes if d.dispute_type == DisputeType.CHARGEBACK and d.status != DisputeResultState.REJECT),
                Decimal("0.00"),
            )
            refundable_exposure = txn.amount - total_refunded - existing_active_cbs

            if std_amount > refundable_exposure:
                dispute = Dispute(
                    dispute_id=dsp_id,
                    txn_id=target_txn_id,
                    dispute_type=dtype,
                    raised_by=norm_raised_by,
                    amount=std_amount,
                    timestamp=ts,
                    status=DisputeResultState.REJECT,
                    deadline=None,
                    rule_ids="BR-09",
                    line_no=line_no,
                )
                self.disputes_by_id[dsp_id] = dispute
                row = DisputeLogRow(
                    line_no=line_no,
                    dispute_id=dsp_id,
                    txn_id=target_txn_id,
                    dispute_type=dtype.value,
                    action="",
                    timestamp=ts,
                    result_state="REJECT",
                    deadline=None,
                    rule_ids="BR-09",
                )
                self.dispute_log.append(row)
                return row

        # 8. Dispute is valid: calculate deadline and target state
        if dtype == DisputeType.TCC:
            deadline = datetime.combine(ts.date() + timedelta(days=5), time(23, 59, 59))
            target_state = DisputeResultState.OPEN_TCC
        else:
            deadline = self.calendar_service.calculate_representment_deadline(ts.date())
            target_state = DisputeResultState.OPEN_CHARGEBACK

        dispute = Dispute(
            dispute_id=dsp_id,
            txn_id=target_txn_id,
            dispute_type=dtype,
            raised_by=norm_raised_by,
            amount=std_amount,
            timestamp=ts,
            status=target_state,
            deadline=deadline,
            rule_ids=None,
            line_no=line_no,
        )
        self.disputes_by_id[dsp_id] = dispute
        if target_txn_id not in self.disputes_by_txn_id:
            self.disputes_by_txn_id[target_txn_id] = []
        self.disputes_by_txn_id[target_txn_id].append(dispute)

        row = DisputeLogRow(
            line_no=line_no,
            dispute_id=dsp_id,
            txn_id=target_txn_id,
            dispute_type=dtype.value,
            action="",
            timestamp=ts,
            result_state=target_state.value,
            deadline=deadline,
            rule_ids=None,
        )
        self.dispute_log.append(row)
        return row

    def handle_dispute_action(
        self,
        command_or_line_no: Union[DisputeActionCommand, int] = 0,
        dispute_id: Optional[str] = None,
        timestamp: Optional[datetime] = None,
        action: Optional[Union[DisputeAction, str]] = None,
        line_no: Optional[int] = None,
    ) -> DisputeLogRow:
        """Process a dispute action (ACCEPT, REPRESENT, PRE_ARBITRATION, CLOSE).

        Enforces strict state transitions, statutory deadlines, auto-acceptance, and financial settlement.
        """
        if isinstance(command_or_line_no, DisputeActionCommand):
            actual_line_no = command_or_line_no.line_no
            dsp_id = command_or_line_no.dispute_id
            ts = command_or_line_no.timestamp
            raw_action = command_or_line_no.action
        else:
            actual_line_no = line_no if line_no is not None else (command_or_line_no if isinstance(command_or_line_no, int) else 0)
            dsp_id = dispute_id or ""
            ts = timestamp or datetime.now()
            raw_action = action or ""

        line_no = actual_line_no

        dispute = self.disputes_by_id.get(dsp_id)
        action_str = raw_action.value if hasattr(raw_action, "value") else str(raw_action).strip().upper()

        # 1. Lookup dispute
        if dispute is None:
            row = DisputeLogRow(
                line_no=line_no,
                dispute_id=dsp_id,
                txn_id="UNKNOWN",
                dispute_type="UNKNOWN",
                action=action_str,
                timestamp=ts,
                result_state="REJECT",
                deadline=None,
                rule_ids="INVALID_DISPUTE",
            )
            self.dispute_log.append(row)
            return row

        if action_str not in ("ACCEPT", "REPRESENT", "PRE_ARBITRATION", "CLOSE"):
            row = DisputeLogRow(
                line_no=line_no,
                dispute_id=dsp_id,
                txn_id=dispute.txn_id,
                dispute_type=dispute.dispute_type.value,
                action=action_str,
                timestamp=ts,
                result_state="REJECT",
                deadline=None,
                rule_ids="INVALID_ACTION",
            )
            self.dispute_log.append(row)
            return row

        act = DisputeAction(action_str)

        # 2. Check Auto-Acceptance on current dispute before processing action
        self.check_and_apply_auto_acceptance(dispute, ts)
        previous_state = dispute.status

        # 3. Reject actions on terminated or resolved disputes (BR-10)
        if previous_state in (
            DisputeResultState.REJECT,
            DisputeResultState.AUTO_ACCEPTED,
            DisputeResultState.CLOSED,
        ):
            row = DisputeLogRow(
                line_no=line_no,
                dispute_id=dsp_id,
                txn_id=dispute.txn_id,
                dispute_type=dispute.dispute_type.value,
                action=act.value,
                timestamp=ts,
                result_state="REJECT",
                deadline=None,
                rule_ids="BR-10",
            )
            self.dispute_log.append(row)
            return row

        # 4. Evaluate State Machine Transitions
        if act == DisputeAction.ACCEPT:
            # ACCEPT is valid ONLY from OPEN_TCC or OPEN_CHARGEBACK
            if previous_state not in (DisputeResultState.OPEN_TCC, DisputeResultState.OPEN_CHARGEBACK):
                row = DisputeLogRow(
                    line_no=line_no,
                    dispute_id=dsp_id,
                    txn_id=dispute.txn_id,
                    dispute_type=dispute.dispute_type.value,
                    action=act.value,
                    timestamp=ts,
                    result_state="REJECT",
                    deadline=None,
                    rule_ids="BR-10",
                )
                self.dispute_log.append(row)
                return row

            dispute.status = DisputeResultState.ACCEPTED
            dispute.deadline = None
            dispute.action = act
            dispute.resolved_at = ts

            # Explicit financial settlement on Chargeback acceptance
            if dispute.dispute_type == DisputeType.CHARGEBACK:
                self._settle_chargeback(dispute, ts)

            row = DisputeLogRow(
                line_no=line_no,
                dispute_id=dsp_id,
                txn_id=dispute.txn_id,
                dispute_type=dispute.dispute_type.value,
                action=act.value,
                timestamp=ts,
                result_state="ACCEPTED",
                deadline=None,
                rule_ids=None,
            )
            self.dispute_log.append(row)
            return row

        elif act == DisputeAction.REPRESENT:
            # REPRESENT is valid ONLY from OPEN_CHARGEBACK
            if previous_state != DisputeResultState.OPEN_CHARGEBACK:
                row = DisputeLogRow(
                    line_no=line_no,
                    dispute_id=dsp_id,
                    txn_id=dispute.txn_id,
                    dispute_type=dispute.dispute_type.value,
                    action=act.value,
                    timestamp=ts,
                    result_state="REJECT",
                    deadline=None,
                    rule_ids="BR-10",
                )
                self.dispute_log.append(row)
                return row

            # Must be within 5-working-day representment deadline
            if dispute.deadline is not None and ts > dispute.deadline:
                row = DisputeLogRow(
                    line_no=line_no,
                    dispute_id=dsp_id,
                    txn_id=dispute.txn_id,
                    dispute_type=dispute.dispute_type.value,
                    action=act.value,
                    timestamp=ts,
                    result_state="REJECT",
                    deadline=None,
                    rule_ids="BR-10",
                )
                self.dispute_log.append(row)
                return row

            dispute.status = DisputeResultState.REPRESENTED
            dispute.action = act
            new_deadline = self.calendar_service.calculate_pre_arbitration_deadline(ts.date())
            dispute.deadline = new_deadline

            row = DisputeLogRow(
                line_no=line_no,
                dispute_id=dsp_id,
                txn_id=dispute.txn_id,
                dispute_type=dispute.dispute_type.value,
                action=act.value,
                timestamp=ts,
                result_state="REPRESENTED",
                deadline=new_deadline,
                rule_ids=None,
            )
            self.dispute_log.append(row)
            return row

        elif act == DisputeAction.PRE_ARBITRATION:
            # PRE_ARBITRATION is valid ONLY from REPRESENTED
            if previous_state != DisputeResultState.REPRESENTED:
                row = DisputeLogRow(
                    line_no=line_no,
                    dispute_id=dsp_id,
                    txn_id=dispute.txn_id,
                    dispute_type=dispute.dispute_type.value,
                    action=act.value,
                    timestamp=ts,
                    result_state="REJECT",
                    deadline=None,
                    rule_ids="BR-10",
                )
                self.dispute_log.append(row)
                return row

            # Must be within 7-calendar-day deadline
            if dispute.deadline is not None and ts > dispute.deadline:
                row = DisputeLogRow(
                    line_no=line_no,
                    dispute_id=dsp_id,
                    txn_id=dispute.txn_id,
                    dispute_type=dispute.dispute_type.value,
                    action=act.value,
                    timestamp=ts,
                    result_state="REJECT",
                    deadline=None,
                    rule_ids="BR-10",
                )
                self.dispute_log.append(row)
                return row

            dispute.status = DisputeResultState.PRE_ARBITRATION
            dispute.action = act
            dispute.deadline = None

            row = DisputeLogRow(
                line_no=line_no,
                dispute_id=dsp_id,
                txn_id=dispute.txn_id,
                dispute_type=dispute.dispute_type.value,
                action=act.value,
                timestamp=ts,
                result_state="PRE_ARBITRATION",
                deadline=None,
                rule_ids=None,
            )
            self.dispute_log.append(row)
            return row

        elif act == DisputeAction.CLOSE:
            # CLOSE is valid from PRE_ARBITRATION or ACCEPTED
            if previous_state not in (DisputeResultState.PRE_ARBITRATION, DisputeResultState.ACCEPTED):
                row = DisputeLogRow(
                    line_no=line_no,
                    dispute_id=dsp_id,
                    txn_id=dispute.txn_id,
                    dispute_type=dispute.dispute_type.value,
                    action=act.value,
                    timestamp=ts,
                    result_state="REJECT",
                    deadline=None,
                    rule_ids="BR-10",
                )
                self.dispute_log.append(row)
                return row

            dispute.status = DisputeResultState.CLOSED
            dispute.action = act
            dispute.deadline = None
            dispute.resolved_at = ts

            row = DisputeLogRow(
                line_no=line_no,
                dispute_id=dsp_id,
                txn_id=dispute.txn_id,
                dispute_type=dispute.dispute_type.value,
                action=act.value,
                timestamp=ts,
                result_state="CLOSED",
                deadline=None,
                rule_ids=None,
            )
            self.dispute_log.append(row)
            return row

        # Fallback for unexpected action
        row = DisputeLogRow(
            line_no=line_no,
            dispute_id=dsp_id,
            txn_id=dispute.txn_id,
            dispute_type=dispute.dispute_type.value,
            action=action_str,
            timestamp=ts,
            result_state="REJECT",
            deadline=None,
            rule_ids="BR-10",
        )
        self.dispute_log.append(row)
        return row
