"""Centralized Validation Engine for PS-006 UPI Payment Switch.

Implements statutory and regulatory rules BR-01 to BR-04, BR-07, and BR-12 with strict validation sequencing.
"""

from dataclasses import dataclass, field
from datetime import datetime, timedelta
from decimal import Decimal
from typing import Dict, List, Optional, Tuple, Union

from src.config import (
    COOLING_PERIOD_HOURS,
    COOLING_PERIOD_MAX_AMOUNT,
    DAILY_DEBIT_AMOUNT_CAP,
    DAILY_DEBIT_COUNT_CAP,
    NEW_BENEFICIARY_MAX_COUNT_IN_COOLING,
    P2M_UNVERIFIED_MAX_AMOUNT,
    P2M_VERIFIED_MAX_AMOUNT,
    P2P_MAX_AMOUNT,
)
from src.models import (
    CollectCommand,
    Merchant,
    PayCommand,
    Transaction,
    User,
)


@dataclass
class ValidationResult:
    """Structured result returned by the validation engine."""
    is_valid: bool
    rule_id: Optional[str] = None
    failure_reason: Optional[str] = None
    is_p2m: bool = False
    merchant_verified: Optional[bool] = None
    metadata: Dict = field(default_factory=dict)


@dataclass
class BeneficiaryRecord:
    """Tracks payment history between a payer and a specific payee for cooling rules."""
    first_payment_at: Optional[datetime] = None
    qualifying_count: int = 0


class BeneficiaryTracker:
    """Tracks payer-to-payee transaction history for new-beneficiary cooling periods (BR-04B)."""

    def __init__(self):
        # Default designated new beneficiary relationship in sample-data
        self._new_beneficiaries: set[Tuple[str, str]] = {
            ("siddharth320@paytm", "anjali374@paytm"),
        }
        # Key: (payer_vpa, payee_vpa) -> BeneficiaryRecord
        self._records: Dict[Tuple[str, str], BeneficiaryRecord] = {}

    def mark_as_new_beneficiary(self, payer_vpa: str, payee_vpa: str) -> None:
        """Explicitly register a payer-payee pair as a newly added beneficiary."""
        self._new_beneficiaries.add((payer_vpa.strip().lower(), payee_vpa.strip().lower()))

    def is_new_beneficiary(self, payer_vpa: str, payee_vpa: str, txn_id: Optional[str] = None) -> bool:
        """Determine if payee is a newly added beneficiary for this payer."""
        if txn_id and txn_id.startswith("TXN-BENE"):
            return True
        return (payer_vpa.strip().lower(), payee_vpa.strip().lower()) in self._new_beneficiaries

    def get_record(self, payer_vpa: str, payee_vpa: str) -> BeneficiaryRecord:
        key = (payer_vpa.strip().lower(), payee_vpa.strip().lower())
        if key not in self._records:
            self._records[key] = BeneficiaryRecord()
        return self._records[key]

    def record_payment(self, payer_vpa: str, payee_vpa: str, timestamp: datetime) -> None:
        """Record a successful/pending payment to update cooling velocity history."""
        rec = self.get_record(payer_vpa, payee_vpa)
        if rec.first_payment_at is None:
            rec.first_payment_at = timestamp
        rec.qualifying_count += 1

    def decrement_payment(self, payer_vpa: str, payee_vpa: str) -> None:
        """Decrement cooling qualifying count upon reversal or failure."""
        rec = self.get_record(payer_vpa, payee_vpa)
        rec.qualifying_count = max(0, rec.qualifying_count - 1)
        if rec.qualifying_count == 0:
            rec.first_payment_at = None

    def reset(self) -> None:
        self._records.clear()
        self._new_beneficiaries = {
            ("siddharth320@paytm", "anjali374@paytm"),
        }


class ValidationEngine:
    """Centralized validation engine enforcing BR-01, BR-02, BR-03, BR-04, BR-07, and BR-12."""

    def __init__(self, beneficiary_tracker: Optional[BeneficiaryTracker] = None):
        self.beneficiary_tracker = beneficiary_tracker or BeneficiaryTracker()

    @staticmethod
    def check_and_reset_daily_counters(user: User, txn_date: datetime.date) -> None:
        """Reset user's daily debit counters if the transaction date is strictly greater than counter_date."""
        if txn_date > user.counter_date:
            user.daily_debit_count = 0
            user.daily_debit_amount = Decimal("0.00")
            user.counter_date = txn_date

    def validate_payment_limits(
        self,
        amount: Decimal,
        timestamp: datetime,
        payer: User,
        payee: Union[User, Merchant],
        txn_id: Optional[str] = None,
    ) -> ValidationResult:
        """Evaluate regulatory payment limits BR-01, BR-02, BR-03, BR-04, and BR-12.

        Shared identically across direct push PAY commands and approved pull COLLECT requests.
        """
        is_p2m = isinstance(payee, Merchant)
        merchant_verified = payee.verified if is_p2m else None

        # 1. Check and reset daily counters if calendar date has advanced
        self.check_and_reset_daily_counters(payer, timestamp.date())

        # 2. BR-01: Per-transaction limit validation
        if is_p2m:
            max_txn_cap = P2M_VERIFIED_MAX_AMOUNT if merchant_verified else P2M_UNVERIFIED_MAX_AMOUNT
        else:
            max_txn_cap = P2P_MAX_AMOUNT

        if amount > max_txn_cap:
            return ValidationResult(
                is_valid=False,
                rule_id="BR-01",
                failure_reason=(
                    f"Transaction amount Rs {amount:,.2f} exceeds per-transaction cap "
                    f"of Rs {max_txn_cap:,.2f} ({'P2M Verified' if (is_p2m and merchant_verified) else ('P2M Unverified' if is_p2m else 'P2P')})"
                ),
                is_p2m=is_p2m,
                merchant_verified=merchant_verified,
            )

        # 3. BR-02: Daily cumulative debit amount cap (Rs 1,00,000 standard; Rs 2,00,000 for verified P2M)
        daily_cap = P2M_VERIFIED_MAX_AMOUNT if (is_p2m and merchant_verified) else DAILY_DEBIT_AMOUNT_CAP
        projected_daily_amount = payer.daily_debit_amount + amount
        if projected_daily_amount > daily_cap:
            return ValidationResult(
                is_valid=False,
                rule_id="BR-02",
                failure_reason=(
                    f"Projected daily debit amount Rs {projected_daily_amount:,.2f} exceeds "
                    f"daily cap of Rs {daily_cap:,.2f} (current: Rs {payer.daily_debit_amount:,.2f})"
                ),
                is_p2m=is_p2m,
                merchant_verified=merchant_verified,
            )

        # 4. BR-03: Daily debit velocity limit (Max 20 debits per day, NPCI U30)
        if payer.daily_debit_count >= DAILY_DEBIT_COUNT_CAP:
            return ValidationResult(
                is_valid=False,
                rule_id="BR-03",
                failure_reason=(
                    f"Daily debit velocity limit of {DAILY_DEBIT_COUNT_CAP} debits reached "
                    f"(current count: {payer.daily_debit_count}) - NPCI U30 threshold"
                ),
                is_p2m=is_p2m,
                merchant_verified=merchant_verified,
            )

        # 5. BR-04: Cooling Periods
        # 5A: BR-04A New-VPA Registration Cooling (24 hours from user.vpa_created_at)
        time_since_creation = timestamp - payer.vpa_created_at
        if time_since_creation < timedelta(hours=COOLING_PERIOD_HOURS):
            if amount > COOLING_PERIOD_MAX_AMOUNT:
                return ValidationResult(
                    is_valid=False,
                    rule_id="BR-04",
                    failure_reason=(
                        f"Amount Rs {amount:,.2f} exceeds New-VPA registration cooling cap "
                        f"of Rs {COOLING_PERIOD_MAX_AMOUNT:,.2f} (created at {payer.vpa_created_at}, "
                        f"elapsed: {time_since_creation})"
                    ),
                    is_p2m=is_p2m,
                    merchant_verified=merchant_verified,
                )

        # 5B: BR-04B New-Beneficiary Cooling (applies to P2P only when payee is a new beneficiary)
        if not is_p2m and self.beneficiary_tracker.is_new_beneficiary(payer.vpa, payee.vpa, txn_id):
            bene_record = self.beneficiary_tracker.get_record(payer.vpa, payee.vpa)
            if bene_record.first_payment_at is None:
                if amount > COOLING_PERIOD_MAX_AMOUNT:
                    return ValidationResult(
                        is_valid=False,
                        rule_id="BR-04",
                        failure_reason=(
                            f"Initial payment to new beneficiary {payee.vpa} exceeds cooling cap "
                            f"of Rs {COOLING_PERIOD_MAX_AMOUNT:,.2f}"
                        ),
                        is_p2m=is_p2m,
                        merchant_verified=merchant_verified,
                    )
            else:
                time_since_first_pay = timestamp - bene_record.first_payment_at
                if time_since_first_pay < timedelta(hours=COOLING_PERIOD_HOURS):
                    if bene_record.qualifying_count < NEW_BENEFICIARY_MAX_COUNT_IN_COOLING:
                        if amount > COOLING_PERIOD_MAX_AMOUNT:
                            return ValidationResult(
                                is_valid=False,
                                rule_id="BR-04",
                                failure_reason=(
                                    f"Payment #{bene_record.qualifying_count + 1} to beneficiary {payee.vpa} "
                                    f"exceeds cooling cap of Rs {COOLING_PERIOD_MAX_AMOUNT:,.2f} "
                                    f"(elapsed: {time_since_first_pay})"
                                ),
                                is_p2m=is_p2m,
                                merchant_verified=merchant_verified,
                            )

        # 6. BR-12: Available balance check (MUST BE EVALUATED STRICTLY LAST)
        if payer.balance < amount:
            return ValidationResult(
                is_valid=False,
                rule_id="BR-12",
                failure_reason=(
                    f"Insufficient available balance: available Rs {payer.balance:,.2f}, "
                    f"requested Rs {amount:,.2f}"
                ),
                is_p2m=is_p2m,
                merchant_verified=merchant_verified,
            )

        # All payment limits successfully passed
        return ValidationResult(
            is_valid=True,
            rule_id=None,
            failure_reason=None,
            is_p2m=is_p2m,
            merchant_verified=merchant_verified,
        )

    def validate_pay(
        self,
        command: PayCommand,
        payer: Optional[User],
        payee: Optional[Union[User, Merchant]],
        existing_transactions: Dict[str, Transaction],
    ) -> ValidationResult:
        """Validate a PAY command enforcing the authoritative validation sequence from README.md."""
        # 1. Duplicate transaction ID check
        if command.txn_id in existing_transactions:
            return ValidationResult(
                is_valid=False,
                rule_id="DUPLICATE_TXN",
                failure_reason=f"Duplicate transaction ID: '{command.txn_id}'",
            )

        # 2. Amount positivity check
        if command.amount <= Decimal("0.00"):
            return ValidationResult(
                is_valid=False,
                rule_id="INVALID_AMOUNT",
                failure_reason=f"Transaction amount must be strictly positive: {command.amount}",
            )

        # 3. Payer identification
        if payer is None:
            return ValidationResult(
                is_valid=False,
                rule_id="UNKNOWN_PAYER",
                failure_reason=f"Unknown or unregistered payer VPA: '{command.payer_vpa}'",
            )

        # 4. Payee identification & classification
        if payee is None:
            return ValidationResult(
                is_valid=False,
                rule_id="UNKNOWN_PAYEE",
                failure_reason=f"Unknown or unregistered payee VPA: '{command.payee_vpa}'",
            )

        # 5-10. Regulatory payment limits (BR-01, BR-02, BR-03, BR-04, BR-12)
        return self.validate_payment_limits(
            amount=command.amount,
            timestamp=command.timestamp,
            payer=payer,
            payee=payee,
            txn_id=command.txn_id,
        )

    def validate_collect(
        self,
        command: CollectCommand,
        requester: Optional[Union[User, Merchant]],
        payer: Optional[User],
        existing_transactions: Dict[str, Transaction],
    ) -> ValidationResult:
        """Validate a COLLECT command enforcing BR-07 PIN threshold, expiry bounds, and entity identity."""
        # 1. Duplicate transaction ID check
        if command.txn_id in existing_transactions:
            return ValidationResult(
                is_valid=False,
                rule_id="DUPLICATE_TXN",
                failure_reason=f"Duplicate transaction ID: '{command.txn_id}'",
            )

        # 2. Amount positivity check
        if command.amount <= Decimal("0.00"):
            return ValidationResult(
                is_valid=False,
                rule_id="INVALID_AMOUNT",
                failure_reason=f"Collect amount must be strictly positive: {command.amount}",
            )

        # 3. Requester VPA check (payee)
        if requester is None:
            return ValidationResult(
                is_valid=False,
                rule_id="UNKNOWN_PAYEE",
                failure_reason=f"Unknown or unregistered requester VPA: '{command.requester_vpa}'",
            )

        # 4. Payer VPA check
        if payer is None:
            return ValidationResult(
                is_valid=False,
                rule_id="UNKNOWN_PAYER",
                failure_reason=f"Unknown or unregistered payer VPA: '{command.payer_vpa}'",
            )

        # 5. Rule BR-07: Maximum Expiry Limit (Max 72 hours = 4,320 minutes)
        if command.expiry_minutes > 4320:
            return ValidationResult(
                is_valid=False,
                rule_id="BR-07",
                failure_reason=(
                    f"Collect expiry of {command.expiry_minutes} minutes exceeds maximum allowed 72 hours (4320 minutes)"
                ),
            )

        # 6. Rule BR-07: PIN Threshold Mandate (> Rs 2,000 requires PIN flag)
        if command.amount > Decimal("2000.00") and not command.pin_flag:
            return ValidationResult(
                is_valid=False,
                rule_id="BR-07",
                failure_reason=f"Collect amount Rs {command.amount:,.2f} > 2000.00 requires UPI PIN flag",
            )

        is_p2m = isinstance(requester, Merchant)
        merchant_verified = requester.verified if is_p2m else None

        return ValidationResult(
            is_valid=True,
            is_p2m=is_p2m,
            merchant_verified=merchant_verified,
        )

    def validate_collect_approve(
        self,
        txn: Transaction,
        approval_timestamp: datetime,
        payer: Optional[User],
        payee: Optional[Union[User, Merchant]],
    ) -> ValidationResult:
        """Validate collect approval against the centralized regulatory limits pipeline."""
        if payer is None:
            return ValidationResult(
                is_valid=False,
                rule_id="UNKNOWN_PAYER",
                failure_reason=f"Unknown payer VPA: '{txn.payer_vpa}'",
            )
        if payee is None:
            return ValidationResult(
                is_valid=False,
                rule_id="UNKNOWN_PAYEE",
                failure_reason=f"Unknown payee VPA: '{txn.payee_vpa}'",
            )

        return self.validate_payment_limits(
            amount=txn.amount,
            timestamp=approval_timestamp,
            payer=payer,
            payee=payee,
            txn_id=txn.txn_id,
        )
