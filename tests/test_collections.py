"""Comprehensive unit and integration tests for Phase 5: UPI Collect Request Processing."""

import unittest
from datetime import date, datetime, timedelta
from decimal import Decimal

from src.collect import CollectProcessingError
from src.data import load_master_data
from src.ledger import DoubleEntryLedger
from src.models import (
    CollectApproveCommand,
    CollectCommand,
    CollectDeclineCommand,
    TransactionStatus,
)
from src.payments import PaymentService


class TestCollectProcessing(unittest.TestCase):
    """Test suite covering UPI Collect Request lifecycle, Rule BR-07, approval, decline, and invariants."""

    def setUp(self):
        self.master_data = load_master_data()
        self.ledger = DoubleEntryLedger()
        self.service = PaymentService(
            master_data=self.master_data,
            ledger=self.ledger,
        )

    # -----------------------------------------------------------------------
    # 1. Collect Creation (Scenarios 1-10)
    # -----------------------------------------------------------------------

    def test_01_valid_collect_request(self):
        """1. Valid collect request creates PENDING transaction without debiting funds."""
        cmd = CollectCommand(
            line_no=1, command_name="COLLECT", raw_line="",
            txn_id="TXN-C01", timestamp=datetime(2026, 10, 11, 12, 0, 0),
            requester_vpa="udupi@icici", payer_vpa="geetha308@ybl",
            amount=Decimal("2000.00"), expiry_minutes=30, pin_flag=False,
        )
        payer = self.master_data.users_by_vpa["geetha308@ybl"]
        initial_balance = payer.balance

        txn = self.service.process_collect(cmd)

        self.assertEqual(txn.status, TransactionStatus.PENDING)
        self.assertEqual(txn.payer_vpa, "geetha308@ybl")
        self.assertEqual(txn.payee_vpa, "udupi@icici")
        self.assertEqual(txn.amount, Decimal("2000.00"))
        # Verify payer funds are NOT debited at initiation
        self.assertEqual(payer.balance, initial_balance)
        self.assertEqual(self.ledger.clearing_balance, Decimal("0.00"))

    def test_02_unknown_requester_vpa(self):
        """2. Unknown requester VPA is rejected with UNKNOWN_PAYEE."""
        cmd = CollectCommand(
            line_no=2, command_name="COLLECT", raw_line="",
            txn_id="TXN-C02", timestamp=datetime(2026, 10, 11, 12, 0, 0),
            requester_vpa="unknown_requester@icici", payer_vpa="geetha308@ybl",
            amount=Decimal("500.00"), expiry_minutes=30, pin_flag=False,
        )
        txn = self.service.process_collect(cmd)

        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertEqual(txn.rule_ids, "UNKNOWN_PAYEE")

    def test_03_unknown_payer_vpa(self):
        """3. Unknown payer VPA is rejected with UNKNOWN_PAYER."""
        cmd = CollectCommand(
            line_no=3, command_name="COLLECT", raw_line="",
            txn_id="TXN-C03", timestamp=datetime(2026, 10, 11, 12, 0, 0),
            requester_vpa="udupi@icici", payer_vpa="unknown_payer@ybl",
            amount=Decimal("500.00"), expiry_minutes=30, pin_flag=False,
        )
        txn = self.service.process_collect(cmd)

        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertEqual(txn.rule_ids, "UNKNOWN_PAYER")

    def test_04_duplicate_transaction_id(self):
        """4. Duplicate transaction ID is rejected with DUPLICATE_TXN."""
        cmd1 = CollectCommand(
            line_no=4, command_name="COLLECT", raw_line="",
            txn_id="TXN-C04", timestamp=datetime(2026, 10, 11, 12, 0, 0),
            requester_vpa="udupi@icici", payer_vpa="geetha308@ybl",
            amount=Decimal("1000.00"), expiry_minutes=30, pin_flag=False,
        )
        self.service.process_collect(cmd1)

        cmd2 = CollectCommand(
            line_no=5, command_name="COLLECT", raw_line="",
            txn_id="TXN-C04", timestamp=datetime(2026, 10, 11, 12, 5, 0),
            requester_vpa="udupi@icici", payer_vpa="geetha308@ybl",
            amount=Decimal("1000.00"), expiry_minutes=30, pin_flag=False,
        )
        txn2 = self.service.process_collect(cmd2)

        self.assertEqual(txn2.status, TransactionStatus.FAILED)
        self.assertEqual(txn2.rule_ids, "DUPLICATE_TXN")

    def test_05_zero_amount(self):
        """5. Zero amount is rejected with INVALID_AMOUNT."""
        cmd = CollectCommand(
            line_no=6, command_name="COLLECT", raw_line="",
            txn_id="TXN-C05", timestamp=datetime(2026, 10, 11, 12, 0, 0),
            requester_vpa="udupi@icici", payer_vpa="geetha308@ybl",
            amount=Decimal("0.00"), expiry_minutes=30, pin_flag=False,
        )
        txn = self.service.process_collect(cmd)

        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertEqual(txn.rule_ids, "INVALID_AMOUNT")

    def test_06_negative_amount(self):
        """6. Negative amount is rejected with INVALID_AMOUNT."""
        cmd = CollectCommand(
            line_no=7, command_name="COLLECT", raw_line="",
            txn_id="TXN-C06", timestamp=datetime(2026, 10, 11, 12, 0, 0),
            requester_vpa="udupi@icici", payer_vpa="geetha308@ybl",
            amount=Decimal("-500.00"), expiry_minutes=30, pin_flag=False,
        )
        txn = self.service.process_collect(cmd)

        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertEqual(txn.rule_ids, "INVALID_AMOUNT")

    def test_07_exactly_2000_without_pin(self):
        """7. Exactly Rs 2,000.00 without PIN is allowed (Rule BR-07 boundary)."""
        cmd = CollectCommand(
            line_no=49, command_name="COLLECT", raw_line="",
            txn_id="TXN-0019", timestamp=datetime(2026, 10, 11, 12, 0, 0),
            requester_vpa="udupi@icici", payer_vpa="geetha308@ybl",
            amount=Decimal("2000.00"), expiry_minutes=30, pin_flag=False,
        )
        txn = self.service.process_collect(cmd)

        self.assertEqual(txn.status, TransactionStatus.PENDING)
        self.assertIsNone(txn.rule_ids)

    def test_08_2000_01_without_pin(self):
        """8. Rs 2,000.01 without PIN is rejected under Rule BR-07."""
        cmd = CollectCommand(
            line_no=51, command_name="COLLECT", raw_line="",
            txn_id="TXN-0020", timestamp=datetime(2026, 10, 11, 12, 10, 0),
            requester_vpa="udupi@icici", payer_vpa="geetha308@ybl",
            amount=Decimal("2000.01"), expiry_minutes=30, pin_flag=False,
        )
        txn = self.service.process_collect(cmd)

        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertEqual(txn.rule_ids, "BR-07")
        self.assertIn("requires UPI PIN", txn.failure_reason)

    def test_09_expiry_greater_than_72_hours(self):
        """9. Expiry of 4,321 minutes (> 72 hours) is rejected under Rule BR-07."""
        cmd = CollectCommand(
            line_no=52, command_name="COLLECT", raw_line="",
            txn_id="TXN-0021", timestamp=datetime(2026, 10, 11, 12, 20, 0),
            requester_vpa="udupi@icici", payer_vpa="hema891@ibl",
            amount=Decimal("3500.00"), expiry_minutes=4321, pin_flag=True,
        )
        txn = self.service.process_collect(cmd)

        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertEqual(txn.rule_ids, "BR-07")
        self.assertIn("exceeds maximum allowed 72 hours", txn.failure_reason)

    def test_10_valid_expiry_boundary(self):
        """10. Expiry of exactly 4,320 minutes (72 hours) succeeds."""
        cmd = CollectCommand(
            line_no=10, command_name="COLLECT", raw_line="",
            txn_id="TXN-C10", timestamp=datetime(2026, 10, 11, 12, 20, 0),
            requester_vpa="udupi@icici", payer_vpa="hema891@ibl",
            amount=Decimal("3500.00"), expiry_minutes=4320, pin_flag=True,
        )
        txn = self.service.process_collect(cmd)

        self.assertEqual(txn.status, TransactionStatus.PENDING)
        self.assertEqual(txn.expiry_minutes, 4320)
        self.assertEqual(txn.expires_at, cmd.timestamp + timedelta(minutes=4320))

    # -----------------------------------------------------------------------
    # 2. Approval (Scenarios 11-20)
    # -----------------------------------------------------------------------

    def test_11_approval_before_expiry(self):
        """11. Approval before expiry succeeds and settles payment."""
        payer = self.master_data.users_by_vpa["geetha308@ybl"]
        payee = self.master_data.merchants_by_vpa["udupi@icici"]
        payer_initial_bal = payer.balance
        payee_initial_bal = payee.balance

        self.service.process_collect(CollectCommand(
            line_no=49, command_name="COLLECT", raw_line="",
            txn_id="TXN-0019", timestamp=datetime(2026, 10, 11, 12, 0, 0),
            requester_vpa="udupi@icici", payer_vpa="geetha308@ybl",
            amount=Decimal("2000.00"), expiry_minutes=30, pin_flag=False,
        ))

        # Approve at 12:05:00 (5 minutes elapsed < 30 minutes)
        approve_cmd = CollectApproveCommand(
            line_no=50, command_name="COLLECT_APPROVE", raw_line="",
            txn_id="TXN-0019", timestamp=datetime(2026, 10, 11, 12, 5, 0),
        )
        approved_txn = self.service.process_collect_approve(approve_cmd)

        self.assertEqual(approved_txn.status, TransactionStatus.SUCCESS)
        self.assertEqual(payer.balance, payer_initial_bal - Decimal("2000.00"))
        self.assertEqual(payee.balance, payee_initial_bal + Decimal("2000.00"))

    def test_12_approval_exactly_at_expiry(self):
        """12. Approval exactly at expiry boundary succeeds."""
        self.service.process_collect(CollectCommand(
            line_no=12, command_name="COLLECT", raw_line="",
            txn_id="TXN-C12", timestamp=datetime(2026, 10, 11, 12, 0, 0),
            requester_vpa="udupi@icici", payer_vpa="geetha308@ybl",
            amount=Decimal("1500.00"), expiry_minutes=30, pin_flag=False,
        ))

        # Approve exactly at 12:30:00 (exactly 30 minutes)
        approve_cmd = CollectApproveCommand(
            line_no=13, command_name="COLLECT_APPROVE", raw_line="",
            txn_id="TXN-C12", timestamp=datetime(2026, 10, 11, 12, 30, 0),
        )
        approved_txn = self.service.process_collect_approve(approve_cmd)

        self.assertEqual(approved_txn.status, TransactionStatus.SUCCESS)

    def test_13_approval_after_expiry(self):
        """13. Approval after expiry marks request EXPIRED without debiting payer."""
        payer = self.master_data.users_by_vpa["keerthi335@okaxis"]
        payer_initial_bal = payer.balance

        # TXN-0022 initiated at 13:00:00 with 30m expiry
        self.service.process_collect(CollectCommand(
            line_no=53, command_name="COLLECT", raw_line="",
            txn_id="TXN-0022", timestamp=datetime(2026, 10, 11, 13, 0, 0),
            requester_vpa="anjali374@paytm", payer_vpa="keerthi335@okaxis",
            amount=Decimal("900.00"), expiry_minutes=30, pin_flag=False,
        ))

        # Approve at 13:31:00 (31 minutes elapsed > 30 minutes)
        approve_cmd = CollectApproveCommand(
            line_no=54, command_name="COLLECT_APPROVE", raw_line="",
            txn_id="TXN-0022", timestamp=datetime(2026, 10, 11, 13, 31, 0),
        )
        expired_txn = self.service.process_collect_approve(approve_cmd)

        self.assertEqual(expired_txn.status, TransactionStatus.EXPIRED)
        self.assertEqual(payer.balance, payer_initial_bal)  # Zero debit

    def test_14_approval_of_unknown_request(self):
        """14. Approval of unknown request raises CollectProcessingError."""
        approve_cmd = CollectApproveCommand(
            line_no=14, command_name="COLLECT_APPROVE", raw_line="",
            txn_id="TXN-NONEXISTENT", timestamp=datetime(2026, 10, 11, 12, 5, 0),
        )
        with self.assertRaises(CollectProcessingError):
            self.service.process_collect_approve(approve_cmd)

    def test_15_approval_after_decline(self):
        """15. Approval after request was declined raises CollectProcessingError."""
        self.service.process_collect(CollectCommand(
            line_no=15, command_name="COLLECT", raw_line="",
            txn_id="TXN-C15", timestamp=datetime(2026, 10, 11, 12, 0, 0),
            requester_vpa="udupi@icici", payer_vpa="geetha308@ybl",
            amount=Decimal("1000.00"), expiry_minutes=30, pin_flag=False,
        ))
        self.service.process_collect_decline(CollectDeclineCommand(
            line_no=16, command_name="COLLECT_DECLINE", raw_line="",
            txn_id="TXN-C15", timestamp=datetime(2026, 10, 11, 12, 5, 0),
        ))

        with self.assertRaises(CollectProcessingError):
            self.service.process_collect_approve(CollectApproveCommand(
                line_no=17, command_name="COLLECT_APPROVE", raw_line="",
                txn_id="TXN-C15", timestamp=datetime(2026, 10, 11, 12, 10, 0),
            ))

    def test_16_duplicate_approval(self):
        """16. Duplicate approval is an idempotent no-op with zero double debit."""
        payer = self.master_data.users_by_vpa["geetha308@ybl"]
        self.service.process_collect(CollectCommand(
            line_no=18, command_name="COLLECT", raw_line="",
            txn_id="TXN-C16", timestamp=datetime(2026, 10, 11, 12, 0, 0),
            requester_vpa="udupi@icici", payer_vpa="geetha308@ybl",
            amount=Decimal("1000.00"), expiry_minutes=30, pin_flag=False,
        ))
        approve_cmd = CollectApproveCommand(
            line_no=19, command_name="COLLECT_APPROVE", raw_line="",
            txn_id="TXN-C16", timestamp=datetime(2026, 10, 11, 12, 5, 0),
        )
        self.service.process_collect_approve(approve_cmd)
        balance_after_approval = payer.balance

        # Second approval
        dup_txn = self.service.process_collect_approve(approve_cmd)
        self.assertEqual(dup_txn.status, TransactionStatus.SUCCESS)
        self.assertEqual(payer.balance, balance_after_approval)  # No second debit

    def test_17_approval_with_insufficient_balance(self):
        """17. Approval when payer has insufficient balance fails Rule BR-12."""
        payer = self.master_data.users_by_vpa["arjun598@paytm"]
        payer.balance = Decimal("500.00")

        self.service.process_collect(CollectCommand(
            line_no=20, command_name="COLLECT", raw_line="",
            txn_id="TXN-C17", timestamp=datetime(2026, 10, 11, 12, 0, 0),
            requester_vpa="udupi@icici", payer_vpa="arjun598@paytm",
            amount=Decimal("1500.00"), expiry_minutes=30, pin_flag=False,
        ))

        res = self.service.process_collect_approve(CollectApproveCommand(
            line_no=21, command_name="COLLECT_APPROVE", raw_line="",
            txn_id="TXN-C17", timestamp=datetime(2026, 10, 11, 12, 5, 0),
        ))

        self.assertEqual(res.status, TransactionStatus.FAILED)
        self.assertEqual(res.rule_ids, "BR-12")
        self.assertEqual(payer.balance, Decimal("500.00"))  # Unchanged

    def test_18_approval_violating_payment_limits(self):
        """18. Approval violating daily cumulative cap fails Rule BR-02."""
        payer = self.master_data.users_by_vpa["geetha308@ybl"]
        payer.daily_debit_amount = Decimal("95000.00")
        payer.counter_date = date(2026, 10, 11)

        self.service.process_collect(CollectCommand(
            line_no=22, command_name="COLLECT", raw_line="",
            txn_id="TXN-C18", timestamp=datetime(2026, 10, 11, 12, 0, 0),
            requester_vpa="udupi@icici", payer_vpa="geetha308@ybl",
            amount=Decimal("10000.00"), expiry_minutes=30, pin_flag=True,
        ))

        # Approval: 95,000 + 10,000 = 105,000 > 100,000 unverified merchant cap
        res = self.service.process_collect_approve(CollectApproveCommand(
            line_no=23, command_name="COLLECT_APPROVE", raw_line="",
            txn_id="TXN-C18", timestamp=datetime(2026, 10, 11, 12, 5, 0),
        ))

        self.assertEqual(res.status, TransactionStatus.FAILED)
        self.assertEqual(res.rule_ids, "BR-02")

    def test_19_approval_with_valid_payment_limits(self):
        """19. Approval within all regulatory limits succeeds."""
        self.service.process_collect(CollectCommand(
            line_no=24, command_name="COLLECT", raw_line="",
            txn_id="TXN-C19", timestamp=datetime(2026, 10, 11, 12, 0, 0),
            requester_vpa="udupi@icici", payer_vpa="geetha308@ybl",
            amount=Decimal("500.00"), expiry_minutes=30, pin_flag=False,
        ))

        res = self.service.process_collect_approve(CollectApproveCommand(
            line_no=25, command_name="COLLECT_APPROVE", raw_line="",
            txn_id="TXN-C19", timestamp=datetime(2026, 10, 11, 12, 5, 0),
        ))
        self.assertEqual(res.status, TransactionStatus.SUCCESS)

    def test_20_correct_counter_updates(self):
        """20. Successful approval increments daily velocity counters."""
        payer = self.master_data.users_by_vpa["geetha308@ybl"]
        initial_amount = payer.daily_debit_amount
        initial_count = payer.daily_debit_count

        self.service.process_collect(CollectCommand(
            line_no=26, command_name="COLLECT", raw_line="",
            txn_id="TXN-C20", timestamp=datetime(2026, 10, 11, 12, 0, 0),
            requester_vpa="udupi@icici", payer_vpa="geetha308@ybl",
            amount=Decimal("1200.00"), expiry_minutes=30, pin_flag=False,
        ))
        self.service.process_collect_approve(CollectApproveCommand(
            line_no=27, command_name="COLLECT_APPROVE", raw_line="",
            txn_id="TXN-C20", timestamp=datetime(2026, 10, 11, 12, 5, 0),
        ))

        self.assertEqual(payer.daily_debit_amount, initial_amount + Decimal("1200.00"))
        self.assertEqual(payer.daily_debit_count, initial_count + 1)

    # -----------------------------------------------------------------------
    # 3. Decline (Scenarios 21-25)
    # -----------------------------------------------------------------------

    def test_21_valid_decline(self):
        """21. Valid decline transitions status to DECLINED."""
        self.service.process_collect(CollectCommand(
            line_no=55, command_name="COLLECT", raw_line="",
            txn_id="TXN-0023", timestamp=datetime(2026, 10, 11, 14, 0, 0),
            requester_vpa="anjali374@paytm", payer_vpa="keerthi335@okaxis",
            amount=Decimal("700.00"), expiry_minutes=60, pin_flag=False,
        ))

        declined_txn = self.service.process_collect_decline(CollectDeclineCommand(
            line_no=56, command_name="COLLECT_DECLINE", raw_line="",
            txn_id="TXN-0023", timestamp=datetime(2026, 10, 11, 14, 10, 0),
        ))

        self.assertEqual(declined_txn.status, TransactionStatus.DECLINED)
        self.assertEqual(declined_txn.declined_at, datetime(2026, 10, 11, 14, 10, 0))

    def test_22_decline_of_unknown_request(self):
        """22. Decline of unknown request raises CollectProcessingError."""
        with self.assertRaises(CollectProcessingError):
            self.service.process_collect_decline(CollectDeclineCommand(
                line_no=28, command_name="COLLECT_DECLINE", raw_line="",
                txn_id="TXN-NONEXISTENT", timestamp=datetime(2026, 10, 11, 14, 10, 0),
            ))

    def test_23_decline_after_approval(self):
        """23. Decline after approval raises CollectProcessingError."""
        self.service.process_collect(CollectCommand(
            line_no=29, command_name="COLLECT", raw_line="",
            txn_id="TXN-C23", timestamp=datetime(2026, 10, 11, 12, 0, 0),
            requester_vpa="udupi@icici", payer_vpa="geetha308@ybl",
            amount=Decimal("1000.00"), expiry_minutes=30, pin_flag=False,
        ))
        self.service.process_collect_approve(CollectApproveCommand(
            line_no=30, command_name="COLLECT_APPROVE", raw_line="",
            txn_id="TXN-C23", timestamp=datetime(2026, 10, 11, 12, 5, 0),
        ))

        with self.assertRaises(CollectProcessingError):
            self.service.process_collect_decline(CollectDeclineCommand(
                line_no=31, command_name="COLLECT_DECLINE", raw_line="",
                txn_id="TXN-C23", timestamp=datetime(2026, 10, 11, 12, 10, 0),
            ))

    def test_24_duplicate_decline(self):
        """24. Duplicate decline is an idempotent no-op."""
        self.service.process_collect(CollectCommand(
            line_no=32, command_name="COLLECT", raw_line="",
            txn_id="TXN-C24", timestamp=datetime(2026, 10, 11, 12, 0, 0),
            requester_vpa="udupi@icici", payer_vpa="geetha308@ybl",
            amount=Decimal("500.00"), expiry_minutes=30, pin_flag=False,
        ))
        cmd = CollectDeclineCommand(
            line_no=33, command_name="COLLECT_DECLINE", raw_line="",
            txn_id="TXN-C24", timestamp=datetime(2026, 10, 11, 12, 5, 0),
        )
        self.service.process_collect_decline(cmd)

        # Duplicate decline
        dup_txn = self.service.process_collect_decline(cmd)
        self.assertEqual(dup_txn.status, TransactionStatus.DECLINED)

    def test_25_declined_request_does_not_consume_velocity(self):
        """25. Declined collect request strictly isolates payer velocity counters."""
        payer = self.master_data.users_by_vpa["keerthi335@okaxis"]
        initial_amount = payer.daily_debit_amount
        initial_count = payer.daily_debit_count

        self.service.process_collect(CollectCommand(
            line_no=34, command_name="COLLECT", raw_line="",
            txn_id="TXN-C25", timestamp=datetime(2026, 10, 11, 14, 0, 0),
            requester_vpa="anjali374@paytm", payer_vpa="keerthi335@okaxis",
            amount=Decimal("700.00"), expiry_minutes=60, pin_flag=False,
        ))
        self.service.process_collect_decline(CollectDeclineCommand(
            line_no=35, command_name="COLLECT_DECLINE", raw_line="",
            txn_id="TXN-C25", timestamp=datetime(2026, 10, 11, 14, 10, 0),
        ))

        self.assertEqual(payer.daily_debit_amount, initial_amount)
        self.assertEqual(payer.daily_debit_count, initial_count)

    # -----------------------------------------------------------------------
    # 4. Accounting & Integration (Scenarios 26-30)
    # -----------------------------------------------------------------------

    def test_26_collect_approval_has_correct_financial_entries(self):
        """26. Approval creates balanced DEBIT and CREDIT double-entry legs."""
        payer = self.master_data.users_by_vpa["geetha308@ybl"]
        payee = self.master_data.merchants_by_vpa["udupi@icici"]

        self.service.process_collect(CollectCommand(
            line_no=36, command_name="COLLECT", raw_line="",
            txn_id="TXN-C26", timestamp=datetime(2026, 10, 11, 12, 0, 0),
            requester_vpa="udupi@icici", payer_vpa="geetha308@ybl",
            amount=Decimal("2000.00"), expiry_minutes=30, pin_flag=False,
        ))
        self.service.process_collect_approve(CollectApproveCommand(
            line_no=37, command_name="COLLECT_APPROVE", raw_line="",
            txn_id="TXN-C26", timestamp=datetime(2026, 10, 11, 12, 5, 0),
        ))

        legs = self.ledger.get_entries_for_txn("TXN-C26")
        self.assertEqual(len(legs), 2)

        payer_legs = [l for l in legs if l.vpa == "geetha308@ybl"]
        payee_legs = [l for l in legs if l.vpa == "udupi@icici"]

        self.assertEqual(len(payer_legs), 1)
        self.assertEqual(payer_legs[0].debit, Decimal("2000.00"))
        self.assertEqual(len(payee_legs), 1)
        self.assertEqual(payee_legs[0].credit, Decimal("2000.00"))

    def test_27_collect_decline_creates_no_payment_debit(self):
        """27. Collect decline creates zero ledger entries."""
        self.service.process_collect(CollectCommand(
            line_no=38, command_name="COLLECT", raw_line="",
            txn_id="TXN-C27", timestamp=datetime(2026, 10, 11, 14, 0, 0),
            requester_vpa="anjali374@paytm", payer_vpa="keerthi335@okaxis",
            amount=Decimal("700.00"), expiry_minutes=60, pin_flag=False,
        ))
        self.service.process_collect_decline(CollectDeclineCommand(
            line_no=39, command_name="COLLECT_DECLINE", raw_line="",
            txn_id="TXN-C27", timestamp=datetime(2026, 10, 11, 14, 10, 0),
        ))

        legs = self.ledger.get_entries_for_txn("TXN-C27")
        self.assertEqual(len(legs), 0)

    def test_28_correct_transaction_logging(self):
        """28. COLLECT, COLLECT_APPROVE, and COLLECT_DECLINE logged in transaction_log."""
        self.service.process_collect(CollectCommand(
            line_no=40, command_name="COLLECT", raw_line="",
            txn_id="TXN-C28", timestamp=datetime(2026, 10, 11, 12, 0, 0),
            requester_vpa="udupi@icici", payer_vpa="geetha308@ybl",
            amount=Decimal("1000.00"), expiry_minutes=30, pin_flag=False,
        ))
        self.service.process_collect_approve(CollectApproveCommand(
            line_no=41, command_name="COLLECT_APPROVE", raw_line="",
            txn_id="TXN-C28", timestamp=datetime(2026, 10, 11, 12, 5, 0),
        ))

        log = self.service.get_transaction_log()
        c_entries = [t for t in log if t.txn_id == "TXN-C28"]
        self.assertEqual(len(c_entries), 2)
        self.assertEqual(c_entries[0].command, "COLLECT")
        self.assertEqual(c_entries[1].command, "COLLECT_APPROVE")
        self.assertEqual(c_entries[1].status, TransactionStatus.SUCCESS)

    def test_29_correct_transaction_status(self):
        """29. Final transactions map reflects final resolved status."""
        self.service.process_collect(CollectCommand(
            line_no=42, command_name="COLLECT", raw_line="",
            txn_id="TXN-C29", timestamp=datetime(2026, 10, 11, 12, 0, 0),
            requester_vpa="udupi@icici", payer_vpa="geetha308@ybl",
            amount=Decimal("1000.00"), expiry_minutes=30, pin_flag=False,
        ))
        self.assertEqual(self.service.get_transaction("TXN-C29").status, TransactionStatus.PENDING)

        self.service.process_collect_approve(CollectApproveCommand(
            line_no=43, command_name="COLLECT_APPROVE", raw_line="",
            txn_id="TXN-C29", timestamp=datetime(2026, 10, 11, 12, 5, 0),
        ))
        self.assertEqual(self.service.get_transaction("TXN-C29").status, TransactionStatus.SUCCESS)

    def test_30_sample_dataset_landmark_scenarios(self):
        """30. Verify official sample commands TXN-0019 to TXN-0023."""
        # Line 49 & 50: TXN-0019 (COLLECT 2000 no PIN -> APPROVE -> SUCCESS)
        t19 = self.service.process_collect(CollectCommand(
            49, "COLLECT", "", "TXN-0019", datetime(2026, 10, 11, 12, 0, 0),
            "udupi@icici", "geetha308@ybl", Decimal("2000.00"), 30, False,
        ))
        self.assertEqual(t19.status, TransactionStatus.PENDING)
        t19_app = self.service.process_collect_approve(CollectApproveCommand(
            50, "COLLECT_APPROVE", "", "TXN-0019", datetime(2026, 10, 11, 12, 5, 0),
        ))
        self.assertEqual(t19_app.status, TransactionStatus.SUCCESS)

        # Line 51: TXN-0020 (COLLECT 2000.01 no PIN -> FAILED BR-07)
        t20 = self.service.process_collect(CollectCommand(
            51, "COLLECT", "", "TXN-0020", datetime(2026, 10, 11, 12, 10, 0),
            "udupi@icici", "geetha308@ybl", Decimal("2000.01"), 30, False,
        ))
        self.assertEqual(t20.status, TransactionStatus.FAILED)
        self.assertEqual(t20.rule_ids, "BR-07")

        # Line 52: TXN-0021 (COLLECT 4321m expiry -> FAILED BR-07)
        t21 = self.service.process_collect(CollectCommand(
            52, "COLLECT", "", "TXN-0021", datetime(2026, 10, 11, 12, 20, 0),
            "udupi@icici", "hema891@ibl", Decimal("3500.00"), 4321, True,
        ))
        self.assertEqual(t21.status, TransactionStatus.FAILED)
        self.assertEqual(t21.rule_ids, "BR-07")

        # Line 53 & 54: TXN-0022 (COLLECT 30m -> APPROVE at 31m -> EXPIRED)
        t22 = self.service.process_collect(CollectCommand(
            53, "COLLECT", "", "TXN-0022", datetime(2026, 10, 11, 13, 0, 0),
            "anjali374@paytm", "keerthi335@okaxis", Decimal("900.00"), 30, False,
        ))
        self.assertEqual(t22.status, TransactionStatus.PENDING)
        t22_app = self.service.process_collect_approve(CollectApproveCommand(
            54, "COLLECT_APPROVE", "", "TXN-0022", datetime(2026, 10, 11, 13, 31, 0),
        ))
        self.assertEqual(t22_app.status, TransactionStatus.EXPIRED)

        # Line 55 & 56: TXN-0023 (COLLECT -> DECLINE -> DECLINED)
        t23 = self.service.process_collect(CollectCommand(
            55, "COLLECT", "", "TXN-0023", datetime(2026, 10, 11, 14, 0, 0),
            "anjali374@paytm", "keerthi335@okaxis", Decimal("700.00"), 60, False,
        ))
        self.assertEqual(t23.status, TransactionStatus.PENDING)
        t23_dec = self.service.process_collect_decline(CollectDeclineCommand(
            56, "COLLECT_DECLINE", "", "TXN-0023", datetime(2026, 10, 11, 14, 10, 0),
        ))
        self.assertEqual(t23_dec.status, TransactionStatus.DECLINED)


if __name__ == "__main__":
    unittest.main()
