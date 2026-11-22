"""Unit tests for Phase 2: Transaction Models & Basic Payment Processing."""

from datetime import datetime
from decimal import Decimal
import unittest

from src.data import load_master_data
from src.ledger import DoubleEntryLedger, SWITCH_CLEARING_VPA
from src.models import (
    LegType,
    PayCommand,
    Transaction,
    TransactionStatus,
)
from src.payments import PaymentProcessingError, PaymentService
from src.rrn_generator import RRNGenerator


class TestPaymentProcessing(unittest.TestCase):
    """Test suite for transaction models, payment flows, accounting, and RRN generation."""

    def setUp(self):
        """Initialize fresh master data, double-entry ledger, and payment service."""
        self.master_data = load_master_data()
        self.ledger = DoubleEntryLedger()
        self.rrn_gen = RRNGenerator(start_seq=1)
        self.payment_service = PaymentService(
            master_data=self.master_data,
            ledger=self.ledger,
            rrn_generator=self.rrn_gen,
        )

        self.initial_total_balance = self.ledger.total_system_balance(
            list(self.master_data.users_by_id.values()),
            list(self.master_data.merchants_by_id.values()),
        )

    def test_transaction_model_creation(self):
        """Verify transaction model fields, defaults, and data types."""
        now = datetime(2026, 10, 1, 9, 10, 0)
        txn = Transaction(
            txn_id="TXN-0001",
            command="PAY",
            timestamp=now,
            payer_vpa="rohit890@okaxis",
            payee_vpa="aditya874@oksbi",
            amount=Decimal("8000.00"),
            status=TransactionStatus.PENDING,
            rrn="000000000001",
            remarks="bill",
        )
        self.assertEqual(txn.txn_id, "TXN-0001")
        self.assertEqual(txn.command, "PAY")
        self.assertEqual(txn.amount, Decimal("8000.00"))
        self.assertEqual(txn.status, TransactionStatus.PENDING)
        self.assertEqual(txn.rrn, "000000000001")
        self.assertEqual(txn.refunded_amount, Decimal("0.00"))
        self.assertIsNone(txn.reversed_at)
        self.assertIsNone(txn.deemed_success_at)

    def test_transaction_status_handling(self):
        """Verify all transaction status enum values."""
        expected_statuses = {
            "SUCCESS",
            "FAILED",
            "PENDING",
            "REVERSED",
            "DEEMED_SUCCESS",
            "RECON_SHORTFALL",
            "EXPIRED",
            "DECLINED",
        }
        actual_statuses = {s.value for s in TransactionStatus}
        self.assertEqual(actual_statuses, expected_statuses)

    def test_valid_p2p_payment(self):
        """Verify successful P2P payment: payer debited to escrow, then settled to payee."""
        payer = self.master_data.get_user("rohit890@okaxis")
        payee = self.master_data.get_user("aditya874@oksbi")
        initial_payer_bal = payer.balance  # 300,000.00
        initial_payee_bal = payee.balance  # 400,000.00
        amount = Decimal("8000.00")

        cmd = PayCommand(
            line_no=3,
            command_name="PAY",
            raw_line="PAY|TXN-0001|2026-10-01 09:10:00|rohit890@okaxis|aditya874@oksbi|8000.00|bill",
            txn_id="TXN-0001",
            timestamp=datetime(2026, 10, 1, 9, 10, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="aditya874@oksbi",
            amount=amount,
            remarks="bill",
        )

        txn = self.payment_service.process_pay(cmd)

        # 1. Verification of initiation state
        self.assertEqual(txn.status, TransactionStatus.PENDING)
        self.assertEqual(txn.rrn, "000000000001")
        self.assertFalse(txn.is_p2m)
        self.assertIsNone(txn.merchant_verified)

        # 2. Accounting check: payer debited, clearing escrow credited, payee untouched
        self.assertEqual(payer.balance, initial_payer_bal - amount)
        self.assertEqual(self.ledger.clearing_balance, amount)
        self.assertEqual(payee.balance, initial_payee_bal)

        # Ledger entries check
        payer_entries = self.ledger.get_entries_for_vpa("rohit890@okaxis")
        self.assertEqual(len(payer_entries), 1)
        self.assertEqual(payer_entries[0].leg_type, LegType.DEBIT)
        self.assertEqual(payer_entries[0].debit, amount)
        self.assertEqual(payer_entries[0].running_balance, initial_payer_bal - amount)

        # Invariant check
        current_total = self.ledger.total_system_balance(
            list(self.master_data.users_by_id.values()),
            list(self.master_data.merchants_by_id.values()),
        )
        self.assertEqual(current_total, self.initial_total_balance)

        # 3. Settle payment (simulating ACK)
        settled_txn = self.payment_service.settle_payment("TXN-0001", datetime(2026, 10, 1, 9, 10, 3))
        self.assertEqual(settled_txn.status, TransactionStatus.SUCCESS)
        self.assertEqual(self.ledger.clearing_balance, Decimal("0.00"))
        self.assertEqual(payee.balance, initial_payee_bal + amount)

        # Invariant check post-settlement
        current_total = self.ledger.total_system_balance(
            list(self.master_data.users_by_id.values()),
            list(self.master_data.merchants_by_id.values()),
        )
        self.assertEqual(current_total, self.initial_total_balance)

    def test_valid_p2m_payment(self):
        """Verify successful P2M payment to verified and unverified merchants."""
        # 1. P2M to Verified Merchant (MER-002: Bengaluru Metro Recharge)
        payer = self.master_data.get_user("hema891@ibl")
        merchant = self.master_data.get_merchant("bengaluru@icici")
        self.assertTrue(merchant.verified)

        initial_payer_bal = payer.balance
        initial_merchant_bal = merchant.balance
        amount = Decimal("1500.00")

        cmd = PayCommand(
            line_no=110,
            command_name="PAY",
            raw_line="PAY|TXN-0037|2026-10-31 09:49:00|hema891@ibl|bengaluru@icici|1500.00|dinner",
            txn_id="TXN-0037",
            timestamp=datetime(2026, 10, 31, 9, 49, 0),
            payer_vpa="hema891@ibl",
            payee_vpa="bengaluru@icici",
            amount=amount,
            remarks="dinner",
        )

        txn = self.payment_service.process_pay(cmd)
        self.assertEqual(txn.status, TransactionStatus.PENDING)
        self.assertTrue(txn.is_p2m)
        self.assertTrue(txn.merchant_verified)
        self.assertEqual(payer.balance, initial_payer_bal - amount)
        self.assertEqual(self.ledger.clearing_balance, amount)

        # Settle
        self.payment_service.settle_payment("TXN-0037", datetime(2026, 10, 31, 9, 49, 5))
        self.assertEqual(merchant.balance, initial_merchant_bal + amount)
        self.assertEqual(self.ledger.clearing_balance, Decimal("0.00"))

        # 2. P2M to Unverified Merchant (MER-001: Nandini Milk Parlour)
        unverified_merchant = self.master_data.get_merchant("nandini@icici")
        self.assertFalse(unverified_merchant.verified)

        cmd2 = PayCommand(
            line_no=38,
            command_name="PAY",
            raw_line="PAY|TXN-0016|2026-10-09 15:00:00|lokesh812@oksbi|nandini@icici|450.00|groceries",
            txn_id="TXN-0016",
            timestamp=datetime(2026, 10, 9, 15, 0, 0),
            payer_vpa="lokesh812@oksbi",
            payee_vpa="nandini@icici",
            amount=Decimal("450.00"),
            remarks="groceries",
        )
        txn2 = self.payment_service.process_pay(cmd2)
        self.assertEqual(txn2.status, TransactionStatus.PENDING)
        self.assertTrue(txn2.is_p2m)
        self.assertFalse(txn2.merchant_verified)

    def test_duplicate_transaction_ids(self):
        """Verify that duplicate transaction IDs are rejected and not re-debited."""
        cmd1 = PayCommand(
            line_no=10,
            command_name="PAY",
            raw_line="PAY|TXN-0034|2026-10-26 09:00:00|geetha308@ybl|aditya874@oksbi|640.00|transfer",
            txn_id="TXN-0034",
            timestamp=datetime(2026, 10, 26, 9, 0, 0),
            payer_vpa="geetha308@ybl",
            payee_vpa="aditya874@oksbi",
            amount=Decimal("640.00"),
            remarks="transfer",
        )
        txn1 = self.payment_service.process_pay(cmd1)
        self.assertEqual(txn1.status, TransactionStatus.PENDING)

        payer = self.master_data.get_user("geetha308@ybl")
        bal_after_first = payer.balance

        # Submit same txn_id again (Line 102 in commands.txt)
        cmd2 = PayCommand(
            line_no=102,
            command_name="PAY",
            raw_line="PAY|TXN-0034|2026-10-26 09:30:00|geetha308@ybl|aditya874@oksbi|640.00|transfer",
            txn_id="TXN-0034",
            timestamp=datetime(2026, 10, 26, 9, 30, 0),
            payer_vpa="geetha308@ybl",
            payee_vpa="aditya874@oksbi",
            amount=Decimal("640.00"),
            remarks="transfer",
        )
        txn2 = self.payment_service.process_pay(cmd2)

        self.assertEqual(txn2.status, TransactionStatus.FAILED)
        self.assertEqual(txn2.rule_ids, "DUPLICATE_TXN")
        self.assertEqual(payer.balance, bal_after_first)  # No second debit

        # Stored transaction in map remains the original one
        self.assertEqual(self.payment_service.get_transaction("TXN-0034").status, TransactionStatus.PENDING)

    def test_unknown_payer(self):
        """Verify that payments with unknown payer VPAs fail immediately."""
        cmd = PayCommand(
            line_no=1,
            command_name="PAY",
            raw_line="PAY|TXN-9999|2026-10-01 10:00:00|unknown@psp|aditya874@oksbi|100.00|test",
            txn_id="TXN-9999",
            timestamp=datetime(2026, 10, 1, 10, 0, 0),
            payer_vpa="unknown@psp",
            payee_vpa="aditya874@oksbi",
            amount=Decimal("100.00"),
            remarks="test",
        )
        txn = self.payment_service.process_pay(cmd)

        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertEqual(txn.rule_ids, "UNKNOWN_PAYER")
        self.assertEqual(self.ledger.clearing_balance, Decimal("0.00"))

    def test_unknown_payee(self):
        """Verify that payments to unknown payee VPAs fail immediately."""
        payer = self.master_data.get_user("hema891@ibl")
        initial_bal = payer.balance

        cmd = PayCommand(
            line_no=105,
            command_name="PAY",
            raw_line="PAY|TXN-0035|2026-10-27 09:00:00|hema891@ibl|ghost99@upi|500.00|rent",
            txn_id="TXN-0035",
            timestamp=datetime(2026, 10, 27, 9, 0, 0),
            payer_vpa="hema891@ibl",
            payee_vpa="ghost99@upi",
            amount=Decimal("500.00"),
            remarks="rent",
        )
        txn = self.payment_service.process_pay(cmd)

        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertEqual(txn.rule_ids, "UNKNOWN_PAYEE")
        self.assertEqual(payer.balance, initial_bal)
        self.assertEqual(self.ledger.clearing_balance, Decimal("0.00"))

    def test_insufficient_balance(self):
        """Verify that payments exceeding available payer balance fail."""
        payer = self.master_data.get_user("arjun598@paytm")  # Opening balance 1500.00
        initial_bal = payer.balance

        cmd = PayCommand(
            line_no=1,
            command_name="PAY",
            raw_line="PAY|TXN-0099|2026-10-01 10:00:00|arjun598@paytm|aditya874@oksbi|5000.00|rent",
            txn_id="TXN-0099",
            timestamp=datetime(2026, 10, 1, 10, 0, 0),
            payer_vpa="arjun598@paytm",
            payee_vpa="aditya874@oksbi",
            amount=Decimal("5000.00"),
            remarks="rent",
        )
        txn = self.payment_service.process_pay(cmd)

        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertIn(txn.rule_ids, ("INSUFFICIENT_BALANCE", "BR-12"))
        self.assertEqual(payer.balance, initial_bal)
        self.assertEqual(self.ledger.clearing_balance, Decimal("0.00"))

    def test_invalid_amounts(self):
        """Verify that zero or negative transaction amounts are rejected."""
        # Zero amount
        cmd_zero = PayCommand(
            line_no=1,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-ZERO",
            timestamp=datetime(2026, 10, 1, 10, 0, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="aditya874@oksbi",
            amount=Decimal("0.00"),
            remarks="zero",
        )
        txn_zero = self.payment_service.process_pay(cmd_zero)
        self.assertEqual(txn_zero.status, TransactionStatus.FAILED)
        self.assertEqual(txn_zero.rule_ids, "INVALID_AMOUNT")

        # Negative amount
        cmd_neg = PayCommand(
            line_no=2,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-NEG",
            timestamp=datetime(2026, 10, 1, 10, 0, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="aditya874@oksbi",
            amount=Decimal("-50.00"),
            remarks="negative",
        )
        txn_neg = self.payment_service.process_pay(cmd_neg)
        self.assertEqual(txn_neg.status, TransactionStatus.FAILED)
        self.assertEqual(txn_neg.rule_ids, "INVALID_AMOUNT")

    def test_decimal_precision(self):
        """Verify that exact decimal arithmetic with 2 decimal places is maintained."""
        payer = self.master_data.get_user("rohit890@okaxis")
        initial_bal = payer.balance  # 300,000.00
        amount = Decimal("150.37")

        cmd = PayCommand(
            line_no=1,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-DEC",
            timestamp=datetime(2026, 10, 1, 10, 0, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="aditya874@oksbi",
            amount=amount,
            remarks="cents",
        )
        txn = self.payment_service.process_pay(cmd)
        self.assertEqual(txn.status, TransactionStatus.PENDING)
        self.assertEqual(payer.balance, initial_bal - Decimal("150.37"))
        self.assertEqual(str(payer.balance), "299849.63")
        self.assertIsInstance(payer.balance, Decimal)

    def test_rrn_length_and_format(self):
        """Verify that RRN is exactly 12 numeric digits."""
        rrn = self.rrn_gen.get_or_create_rrn("TXN-0001")
        self.assertEqual(len(rrn), 12)
        self.assertTrue(rrn.isdigit())
        self.assertEqual(rrn, "000000000001")

    def test_rrn_determinism(self):
        """Verify that RRN generation is reproducible and retains mapping per transaction."""
        # Querying same txn_id returns identical RRN
        rrn1 = self.rrn_gen.get_or_create_rrn("TXN-0001")
        rrn1_again = self.rrn_gen.get_or_create_rrn("TXN-0001")
        self.assertEqual(rrn1, rrn1_again)

        # Fresh generator produces identical sequence
        fresh_gen = RRNGenerator(start_seq=1)
        fresh_rrn1 = fresh_gen.get_or_create_rrn("TXN-0001")
        self.assertEqual(rrn1, fresh_rrn1)

    def test_rrn_uniqueness(self):
        """Verify that distinct transactions receive unique sequential RRNs."""
        rrns = [self.rrn_gen.get_or_create_rrn(f"TXN-{i:04d}") for i in range(1, 101)]
        self.assertEqual(len(rrns), 100)
        self.assertEqual(len(set(rrns)), 100)
        self.assertEqual(rrns[0], "000000000001")
        self.assertEqual(rrns[99], "000000000100")

    def test_payment_reversal_on_rejection(self):
        """Verify that rejecting a PENDING payment restores payer balance from clearing."""
        payer = self.master_data.get_user("rohit890@okaxis")
        initial_bal = payer.balance
        amount = Decimal("5000.00")

        cmd = PayCommand(
            line_no=1,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-REV-TEST",
            timestamp=datetime(2026, 10, 1, 10, 0, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="aditya874@oksbi",
            amount=amount,
            remarks="reversal_test",
        )
        self.payment_service.process_pay(cmd)
        self.assertEqual(payer.balance, initial_bal - amount)
        self.assertEqual(self.ledger.clearing_balance, amount)

        # Reject payment
        rejected_txn = self.payment_service.reject_payment(
            "TXN-REV-TEST", datetime(2026, 10, 1, 10, 0, 4), reason="Beneficiary bank NACK"
        )
        self.assertEqual(rejected_txn.status, TransactionStatus.FAILED)
        self.assertEqual(payer.balance, initial_bal)
        self.assertEqual(self.ledger.clearing_balance, Decimal("0.00"))

        # Invariant check
        current_total = self.ledger.total_system_balance(
            list(self.master_data.users_by_id.values()),
            list(self.master_data.merchants_by_id.values()),
        )
        self.assertEqual(current_total, self.initial_total_balance)


if __name__ == "__main__":
    unittest.main()
