"""Domain models, entities, enums, and command representations for PS-006 UPI Switch."""

from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal
from enum import Enum
from typing import List, Optional, Union


class TransactionStatus(str, Enum):
    """Transaction states supported by the UPI switch."""
    SUCCESS = "SUCCESS"
    FAILED = "FAILED"
    PENDING = "PENDING"
    REVERSED = "REVERSED"
    DEEMED_SUCCESS = "DEEMED_SUCCESS"
    RECON_SHORTFALL = "RECON_SHORTFALL"
    EXPIRED = "EXPIRED"
    DECLINED = "DECLINED"


class LegType(str, Enum):
    """Ledger leg classifications for double-entry movements."""
    DEBIT = "DEBIT"
    CREDIT = "CREDIT"
    REVERSAL = "REVERSAL"
    REFUND = "REFUND"
    DISPUTE_DEBIT = "DISPUTE_DEBIT"
    DISPUTE_CREDIT = "DISPUTE_CREDIT"


class DisputeType(str, Enum):
    """Classification of disputes."""
    TCC = "TCC"
    CHARGEBACK = "CHARGEBACK"


class DisputeAction(str, Enum):
    """Actions performed on open disputes."""
    ACCEPT = "ACCEPT"
    REPRESENT = "REPRESENT"
    PRE_ARBITRATION = "PRE_ARBITRATION"
    CLOSE = "CLOSE"


class DisputeResultState(str, Enum):
    """Lifecycle states of a dispute."""
    OPEN_TCC = "OPEN_TCC"
    OPEN_CHARGEBACK = "OPEN_CHARGEBACK"
    REPRESENTED = "REPRESENTED"
    PRE_ARBITRATION = "PRE_ARBITRATION"
    ACCEPTED = "ACCEPTED"
    AUTO_ACCEPTED = "AUTO_ACCEPTED"
    CLOSED = "CLOSED"
    REJECT = "REJECT"


class BeneResponseType(str, Enum):
    """Beneficiary bank simulation responses."""
    ACK = "ACK"
    NACK = "NACK"
    NO_RESPONSE = "NO_RESPONSE"


@dataclass
class User:
    """Registered UPI user entity with linked bank account and daily counters."""
    user_id: str
    name: str
    vpa: str
    bank_account: str
    ifsc: str
    balance: Decimal
    vpa_created_at: datetime
    device_bound_at: datetime
    mobile: str
    counter_date: date
    daily_debit_count: int
    daily_debit_amount: Decimal
    monthly_debit_amount: Decimal

    def copy(self) -> "User":
        """Return a deep copy of the user instance."""
        return User(
            user_id=self.user_id,
            name=self.name,
            vpa=self.vpa,
            bank_account=self.bank_account,
            ifsc=self.ifsc,
            balance=Decimal(str(self.balance)),
            vpa_created_at=self.vpa_created_at,
            device_bound_at=self.device_bound_at,
            mobile=self.mobile,
            counter_date=self.counter_date,
            daily_debit_count=self.daily_debit_count,
            daily_debit_amount=Decimal(str(self.daily_debit_amount)),
            monthly_debit_amount=Decimal(str(self.monthly_debit_amount)),
        )


@dataclass
class Merchant:
    """Merchant entity with MCC and verification status."""
    merchant_id: str
    name: str
    vpa: str
    mcc: str
    verified: bool
    bank_account: str
    balance: Decimal

    def copy(self) -> "Merchant":
        """Return a deep copy of the merchant instance."""
        return Merchant(
            merchant_id=self.merchant_id,
            name=self.name,
            vpa=self.vpa,
            mcc=self.mcc,
            verified=self.verified,
            bank_account=self.bank_account,
            balance=Decimal(str(self.balance)),
        )


@dataclass(frozen=True)
class Holiday:
    """Bank holiday date and description."""
    date: date
    name: str


@dataclass
class Transaction:
    """Core transaction model tracking lifecycle, amounts, parties, and states."""
    txn_id: str
    command: str
    timestamp: datetime
    payer_vpa: str
    payee_vpa: str
    amount: Decimal
    status: TransactionStatus
    rrn: str
    remarks: str = ""
    refunded_amount: Decimal = Decimal("0.00")
    reversed_at: Optional[datetime] = None
    deemed_success_at: Optional[datetime] = None
    rule_ids: Optional[str] = None
    line_no: int = 0
    failure_reason: Optional[str] = None
    is_p2m: bool = False
    merchant_verified: Optional[bool] = None
    expiry_minutes: Optional[int] = None
    pin_flag: Optional[bool] = None
    expires_at: Optional[datetime] = None
    approved_at: Optional[datetime] = None
    declined_at: Optional[datetime] = None


@dataclass
class CollectRequest:
    """UPI collect (pull payment) request entity tracking validity and authorization state."""
    txn_id: str
    timestamp: datetime
    requester_vpa: str
    payer_vpa: str
    amount: Decimal
    expiry_minutes: int
    pin_flag: bool
    expires_at: datetime
    status: TransactionStatus
    line_no: int
    failure_reason: Optional[str] = None
    rule_ids: Optional[str] = None
    approved_at: Optional[datetime] = None
    declined_at: Optional[datetime] = None


@dataclass
class LedgerEntry:
    """Double-entry ledger journal leg."""
    entry_id: int
    timestamp: datetime
    txn_id: str
    vpa: str
    leg_type: LegType
    amount: Decimal
    debit: Optional[Decimal] = None
    credit: Optional[Decimal] = None
    running_balance: Decimal = Decimal("0.00")


@dataclass
class Dispute:
    """Dispute entity tracking state, deadlines, evidence, and settlement."""
    dispute_id: str
    txn_id: str
    dispute_type: DisputeType
    raised_by: str
    amount: Decimal
    timestamp: datetime
    status: DisputeResultState
    deadline: Optional[datetime] = None
    rule_ids: Optional[str] = None
    line_no: int = 0
    action: Optional[DisputeAction] = None
    resolved_at: Optional[datetime] = None


@dataclass
class DisputeLogRow:
    """Record for dispute_log.csv output."""
    line_no: int
    dispute_id: str
    txn_id: str
    dispute_type: str
    action: str
    timestamp: datetime
    result_state: str
    deadline: Optional[datetime] = None
    rule_ids: Optional[str] = None

    @property
    def type(self) -> str:
        """Alias for dispute_type."""
        return self.dispute_type

    def to_csv_row(self) -> List[str]:
        """Convert to CSV row matching dispute_log.csv schema."""
        ts_str = self.timestamp.strftime("%Y-%m-%d %H:%M:%S")
        dl_str = self.deadline.strftime("%Y-%m-%d %H:%M:%S") if self.deadline else ""
        return [
            str(self.line_no),
            self.dispute_id,
            self.txn_id,
            self.dispute_type,
            self.action or "",
            ts_str,
            self.result_state,
            dl_str,
            self.rule_ids or "",
        ]


@dataclass(frozen=True)
class TxnLogRow:
    """Record representing a single row in txn_log.csv."""
    line_no: int
    txn_id: str
    command: str
    timestamp: datetime
    payer_vpa: str
    payee_vpa: str
    amount: Decimal
    status: str
    rrn: str
    rule_ids: str = ""

    def to_csv_row(self) -> List[str]:
        """Convert to CSV row matching txn_log.csv schema."""
        return [
            str(self.line_no),
            self.txn_id,
            self.command,
            self.timestamp.strftime("%Y-%m-%d %H:%M:%S"),
            self.payer_vpa,
            self.payee_vpa,
            f"{self.amount:.2f}",
            self.status,
            self.rrn,
            self.rule_ids or "",
        ]


@dataclass(frozen=True)
class TxnFinalRow:
    """Record representing a single row in txn_final.csv."""
    txn_id: str
    final_status: str
    amount: Decimal
    refunded_amount: Decimal = Decimal("0.00")
    reversed_at: Optional[datetime] = None
    deemed_success_at: Optional[datetime] = None

    def to_csv_row(self) -> List[str]:
        """Convert to CSV row matching txn_final.csv schema."""
        rev_str = self.reversed_at.strftime("%Y-%m-%d %H:%M:%S") if self.reversed_at else ""
        deemed_str = self.deemed_success_at.strftime("%Y-%m-%d %H:%M:%S") if self.deemed_success_at else ""
        return [
            self.txn_id,
            self.final_status,
            f"{self.amount:.2f}",
            f"{self.refunded_amount:.2f}",
            rev_str,
            deemed_str,
        ]


@dataclass
class RefundResult:
    """Result of a REFUND operation."""
    is_success: bool
    line_no: int
    txn_id: str
    timestamp: datetime
    payer_vpa: str
    payee_vpa: str
    amount: Decimal
    status: str
    rrn: str
    rule_ids: Optional[str] = None
    message: str = ""


@dataclass
class ReconciliationRecord:
    """Audit incident record for network reconciliation anomalies, late ACKs, and shortfalls."""
    record_id: str
    txn_id: str
    timestamp: datetime
    incident_type: str
    amount: Decimal
    payer_balance: Decimal
    deficiency: Decimal
    details: str
    status: str = "PENDING"
    line_no: int = 0


@dataclass(frozen=True)
class StatementRecord:
    """A formatted statement entry for a participant VPA."""
    vpa: str
    timestamp: datetime
    txn_id: str
    leg_type: Union[LegType, str]
    debit: Optional[Decimal]
    credit: Optional[Decimal]
    running_balance: Decimal

    def to_csv_row(self) -> List[str]:
        """Convert to CSV row matching statements.csv schema."""
        debit_str = f"{self.debit:.2f}" if self.debit is not None else ""
        credit_str = f"{self.credit:.2f}" if self.credit is not None else ""
        ts_str = self.timestamp.strftime("%Y-%m-%d %H:%M:%S")
        leg_str = self.leg_type.value if hasattr(self.leg_type, "value") else str(self.leg_type)
        return [
            self.vpa,
            ts_str,
            self.txn_id,
            leg_str,
            debit_str,
            credit_str,
            f"{self.running_balance:.2f}",
        ]


@dataclass
class ReconciliationReport:
    """Audit report verifying double-entry invariants, ledger balances, and escrow integrity."""
    is_balanced: bool
    total_debits: Decimal
    total_credits: Decimal
    debit_credit_variance: Decimal
    total_system_balance: Decimal
    expected_system_balance: Decimal
    system_balance_variance: Decimal
    clearing_balance: Decimal
    pending_escrow_liability: Decimal
    total_accounts_reconciled: int
    accounts_with_mismatch: List[str]
    duplicate_postings: List[str]
    anomalies: List[str]


# ---------------------------------------------------------------------------
# Command Dataclasses
# ---------------------------------------------------------------------------

@dataclass
class Command:
    """Base class for parsed commands preserving physical file line number."""
    line_no: int
    command_name: str
    raw_line: str


@dataclass
class PayCommand(Command):
    """PAY command representation."""
    txn_id: str
    timestamp: datetime
    payer_vpa: str
    payee_vpa: str
    amount: Decimal
    remarks: str


@dataclass
class BeneResponseCommand(Command):
    """BENE_RESPONSE command representation."""
    txn_id: str
    timestamp: datetime
    response: BeneResponseType


@dataclass
class TimeoutJobCommand(Command):
    """TIMEOUT_JOB command representation."""
    timestamp: datetime


@dataclass
class CollectCommand(Command):
    """COLLECT command representation."""
    txn_id: str
    timestamp: datetime
    requester_vpa: str
    payer_vpa: str
    amount: Decimal
    expiry_minutes: int
    pin_flag: bool


@dataclass
class CollectApproveCommand(Command):
    """COLLECT_APPROVE command representation."""
    txn_id: str
    timestamp: datetime


@dataclass
class CollectDeclineCommand(Command):
    """COLLECT_DECLINE command representation."""
    txn_id: str
    timestamp: datetime


@dataclass
class RefundCommand(Command):
    """REFUND command representation."""
    txn_id: str
    timestamp: datetime
    amount: Decimal


@dataclass
class DisputeCommand(Command):
    """DISPUTE command representation."""
    dispute_id: str
    timestamp: datetime
    txn_id: str
    dispute_type: DisputeType
    raised_by: str
    amount: Decimal


@dataclass
class DisputeActionCommand(Command):
    """DISPUTE_ACTION command representation."""
    dispute_id: str
    timestamp: datetime
    action: DisputeAction


@dataclass
class StatementCommand(Command):
    """STATEMENT command representation."""
    vpa: str
    from_date: date
    to_date: date
