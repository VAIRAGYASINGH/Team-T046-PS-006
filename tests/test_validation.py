"""Unit tests for Phase 3: Centralized Validation Engine & Payment Limits (BR-01 to BR-04, BR-12)."""

from datetime import date, datetime, timedelta
from decimal import Decimal
import unittest

from src.data import load_master_data
from src.ledger import DoubleEntryLedger
from src.models import Merchant, PayCommand, TransactionStatus, User
from src.payments import PaymentService
from src.rrn_generator import RRNGenerator
from src.validation import BeneficiaryTracker, ValidationEngine, ValidationResult


class TestValidationEngine(unittest.TestCase):
    """Extensive unit and boundary tests for the Centralized Validation Engine."""

    def setUp(self):
        """Set up fresh master data, ledger, RRN generator, validation engine, and payment service."""
        self.master_data = load_master_data()
        self.ledger = DoubleEntryLedger()
        self.rrn_gen = RRNGenerator(start_seq=1)
        self.bene_tracker = BeneficiaryTracker()
        self.val_engine = ValidationEngine(beneficiary_tracker=self.bene_tracker)
        self.payment_service = PaymentService(
            master_data=self.master_data,
            ledger=self.ledger,
            rrn_generator=self.rrn_gen,
            validation_engine=self.val_engine,
        )

    # -------------------------------------------------------------------------
    # 1. Daily Debit Amount Limits (BR-02) & Exact Boundaries
    # -------------------------------------------------------------------------

    def test_daily_amount_exactly_at_limit(self):
        """Verify that a payment which brings cumulative daily debit to exactly Rs 1,00,000.00 succeeds."""
        user = self.master_data.get_user("USR-001")  # Rohit: starts with 82,000.00
        user.daily_debit_amount = Decimal("90000.00")
        user.counter_date = date(2026, 10, 1)

        cmd = PayCommand(
            line_no=7,
            command_name="PAY",
            raw_line="PAY|TXN-0004|2026-10-01 23:59:59|rohit890@okaxis|lokesh812@oksbi|10000.00|groceries",
            txn_id="TXN-0004",
            timestamp=datetime(2026, 10, 1, 23, 59, 59),
            payer_vpa="rohit890@okaxis",
            payee_vpa="lokesh812@oksbi",
            amount=Decimal("10000.00"),
            remarks="groceries",
        )
        txn = self.payment_service.process_pay(cmd)

        self.assertEqual(txn.status, TransactionStatus.PENDING)
        self.assertEqual(user.daily_debit_amount, Decimal("100000.00"))

    def test_daily_amount_exceeding_limit(self):
        """Verify that a payment exceeding Rs 1,00,000.00 daily cumulative total fails BR-02."""
        user = self.master_data.get_user("USR-001")
        user.daily_debit_amount = Decimal("90000.00")
        user.counter_date = date(2026, 10, 1)

        cmd = PayCommand(
            line_no=6,
            command_name="PAY",
            raw_line="PAY|TXN-0003|2026-10-01 14:00:00|rohit890@okaxis|geetha308@ybl|15000.00|rent",
            txn_id="TXN-0003",
            timestamp=datetime(2026, 10, 1, 14, 0, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="geetha308@ybl",
            amount=Decimal("15000.00"),
            remarks="rent",
        )
        txn = self.payment_service.process_pay(cmd)

        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertEqual(txn.rule_ids, "BR-02")
        # Ensure counter was NOT updated
        self.assertEqual(user.daily_debit_amount, Decimal("90000.00"))

    def test_daily_amount_exceeding_by_one_paisa(self):
        """Verify boundary precision: Rs 100,000.01 daily cumulative fails BR-02."""
        user = self.master_data.get_user("USR-006")  # Siddharth
        user.daily_debit_amount = Decimal("95000.00")
        user.counter_date = date(2026, 10, 1)

        cmd = PayCommand(
            line_no=1,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-BOUND-1",
            timestamp=datetime(2026, 10, 1, 12, 0, 0),
            payer_vpa="siddharth320@paytm",
            payee_vpa="aditya874@oksbi",
            amount=Decimal("5000.01"),
            remarks="test",
        )
        txn = self.payment_service.process_pay(cmd)
        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertEqual(txn.rule_ids, "BR-02")
        self.assertEqual(user.daily_debit_amount, Decimal("95000.00"))

    # -------------------------------------------------------------------------
    # 2. Daily Debit Count / Velocity Limit (BR-03, NPCI U30)
    # -------------------------------------------------------------------------

    def test_daily_count_at_permitted_boundary(self):
        """Verify that the 20th debit on a calendar day succeeds."""
        user = self.master_data.get_user("USR-006")
        user.daily_debit_count = 19
        user.counter_date = date(2026, 10, 1)

        cmd = PayCommand(
            line_no=1,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-20TH",
            timestamp=datetime(2026, 10, 1, 10, 0, 0),
            payer_vpa="siddharth320@paytm",
            payee_vpa="aditya874@oksbi",
            amount=Decimal("100.00"),
            remarks="20th_txn",
        )
        txn = self.payment_service.process_pay(cmd)
        self.assertEqual(txn.status, TransactionStatus.PENDING)
        self.assertEqual(user.daily_debit_count, 20)

    def test_daily_count_exceeded_fails_u30(self):
        """Verify that the 21st debit on a calendar day fails BR-03 (NPCI U30 threshold)."""
        user = self.master_data.get_user("USR-002")  # Harini: opening daily_debit_count = 20
        self.assertEqual(user.daily_debit_count, 20)
        self.assertEqual(user.counter_date, date(2026, 10, 1))

        cmd = PayCommand(
            line_no=5,
            command_name="PAY",
            raw_line="PAY|TXN-0002|2026-10-01 11:30:00|harini730@ibl|hema891@ibl|150.00|rent",
            txn_id="TXN-0002",
            timestamp=datetime(2026, 10, 1, 11, 30, 0),
            payer_vpa="harini730@ibl",
            payee_vpa="hema891@ibl",
            amount=Decimal("150.00"),
            remarks="rent",
        )
        txn = self.payment_service.process_pay(cmd)

        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertEqual(txn.rule_ids, "BR-03")
        # Ensure count did NOT increment beyond 20
        self.assertEqual(user.daily_debit_count, 20)

    # -------------------------------------------------------------------------
    # 3. Midnight Calendar Reset Behaviour
    # -------------------------------------------------------------------------

    def test_midnight_calendar_reset(self):
        """Verify that when the calendar date advances, counters reset to zero at 00:00:00."""
        user = self.master_data.get_user("USR-001")  # Rohit
        user.daily_debit_amount = Decimal("100000.00")
        user.daily_debit_count = 20
        user.counter_date = date(2026, 10, 1)

        # Transaction on next calendar day at 00:00:00
        cmd = PayCommand(
            line_no=9,
            command_name="PAY",
            raw_line="PAY|TXN-0005|2026-10-02 00:00:00|rohit890@okaxis|lokesh812@oksbi|20000.00|groceries",
            txn_id="TXN-0005",
            timestamp=datetime(2026, 10, 2, 0, 0, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="lokesh812@oksbi",
            amount=Decimal("20000.00"),
            remarks="groceries",
        )
        txn = self.payment_service.process_pay(cmd)

        self.assertEqual(txn.status, TransactionStatus.PENDING)
        self.assertEqual(user.counter_date, date(2026, 10, 2))
        self.assertEqual(user.daily_debit_amount, Decimal("20000.00"))
        self.assertEqual(user.daily_debit_count, 1)

    # -------------------------------------------------------------------------
    # 4. New-VPA Registration Cooling (BR-04A)
    # -------------------------------------------------------------------------

    def test_new_vpa_cooling_below_5000(self):
        """Verify that payment <= Rs 5,000 within 24h of VPA creation succeeds."""
        user = self.master_data.get_user("USR-003")  # Vidya: vpa_created_at = 2026-10-03 10:01:00
        cmd = PayCommand(
            line_no=18,
            command_name="PAY",
            raw_line="PAY|TXN-0009|2026-10-04 10:00:30|vidya880@okaxis|aditya874@oksbi|5000.00|rent",
            txn_id="TXN-0009",
            timestamp=datetime(2026, 10, 4, 10, 0, 30),  # 23h 59m 30s
            payer_vpa="vidya880@okaxis",
            payee_vpa="aditya874@oksbi",
            amount=Decimal("5000.00"),
            remarks="rent",
        )
        txn = self.payment_service.process_pay(cmd)
        self.assertEqual(txn.status, TransactionStatus.PENDING)

    def test_new_vpa_cooling_above_5000_fails(self):
        """Verify that payment > Rs 5,000 within 24h of VPA creation fails BR-04."""
        cmd = PayCommand(
            line_no=17,
            command_name="PAY",
            raw_line="PAY|TXN-0008|2026-10-04 10:00:00|vidya880@okaxis|aditya874@oksbi|6000.00|bill",
            txn_id="TXN-0008",
            timestamp=datetime(2026, 10, 4, 10, 0, 0),  # 23h 59m 00s
            payer_vpa="vidya880@okaxis",
            payee_vpa="aditya874@oksbi",
            amount=Decimal("6000.00"),
            remarks="bill",
        )
        txn = self.payment_service.process_pay(cmd)
        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertEqual(txn.rule_ids, "BR-04")

    def test_new_vpa_cooling_exact_24_hours_boundary(self):
        """Verify that payment at exactly 24:00:00 after creation is free from cooling cap."""
        # USR-004 Pradeep: vpa_created_at = 2026-10-04 09:30:00
        cmd = PayCommand(
            line_no=21,
            command_name="PAY",
            raw_line="PAY|TXN-0010|2026-10-05 09:30:00|pradeep458@ibl|aditya874@oksbi|6000.00|dinner",
            txn_id="TXN-0010",
            timestamp=datetime(2026, 10, 5, 9, 30, 0),  # Exactly 24h 00m 00s
            payer_vpa="pradeep458@ibl",
            payee_vpa="aditya874@oksbi",
            amount=Decimal("6000.00"),
            remarks="dinner",
        )
        txn = self.payment_service.process_pay(cmd)
        self.assertEqual(txn.status, TransactionStatus.PENDING)

    # -------------------------------------------------------------------------
    # 5. New-Beneficiary Cooling (BR-04B)
    # -------------------------------------------------------------------------

    def test_new_beneficiary_three_payments_and_24h_boundary(self):
        """Verify that first 3 payments within 24h to a new payee are capped at Rs 5,000, and lift at 24h."""
        # Txn 1: Rs 5,000 (Pass)
        cmd1 = PayCommand(
            line_no=24,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-0011",
            timestamp=datetime(2026, 10, 6, 11, 0, 0),
            payer_vpa="siddharth320@paytm",
            payee_vpa="anjali374@paytm",
            amount=Decimal("5000.00"),
            remarks="rent",
        )
        txn1 = self.payment_service.process_pay(cmd1)
        self.assertEqual(txn1.status, TransactionStatus.PENDING)

        # Txn 2: Rs 4,800 at 12:00:00 (Pass)
        cmd2 = PayCommand(
            line_no=26,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-0012",
            timestamp=datetime(2026, 10, 6, 12, 0, 0),
            payer_vpa="siddharth320@paytm",
            payee_vpa="anjali374@paytm",
            amount=Decimal("4800.00"),
            remarks="dinner",
        )
        txn2 = self.payment_service.process_pay(cmd2)
        self.assertEqual(txn2.status, TransactionStatus.PENDING)

        # Txn 3: Rs 7,000 at 13:00:00 (3rd payment within 24h exceeds 5000 -> Fail BR-04)
        cmd3 = PayCommand(
            line_no=28,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-0013",
            timestamp=datetime(2026, 10, 6, 13, 0, 0),
            payer_vpa="siddharth320@paytm",
            payee_vpa="anjali374@paytm",
            amount=Decimal("7000.00"),
            remarks="dinner",
        )
        txn3 = self.payment_service.process_pay(cmd3)
        self.assertEqual(txn3.status, TransactionStatus.FAILED)
        self.assertEqual(txn3.rule_ids, "BR-04")

        # Txn 4: Exactly 24h after first payment (2026-10-07 11:00:00) -> Rs 7,000 passes
        cmd4 = PayCommand(
            line_no=30,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-0014",
            timestamp=datetime(2026, 10, 7, 11, 0, 0),  # Exactly 24 hours
            payer_vpa="siddharth320@paytm",
            payee_vpa="anjali374@paytm",
            amount=Decimal("7000.00"),
            remarks="groceries",
        )
        txn4 = self.payment_service.process_pay(cmd4)
        self.assertEqual(txn4.status, TransactionStatus.PENDING)

    # -------------------------------------------------------------------------
    # 6. P2M Merchant Caps: Verified vs Unverified (BR-01 & BR-02)
    # -------------------------------------------------------------------------

    def test_p2m_verified_merchant_at_cap(self):
        """Verify that a payment of exactly Rs 2,00,000.00 to a verified merchant succeeds."""
        user = self.master_data.get_user("siddharth320@paytm")
        user.daily_debit_amount = Decimal("0.00")
        user.counter_date = date(2026, 10, 21)

        cmd = PayCommand(
            line_no=1,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-VER-CAP",
            timestamp=datetime(2026, 10, 21, 9, 0, 0),
            payer_vpa="siddharth320@paytm",
            payee_vpa="bengaluru@icici",  # Verified: True
            amount=Decimal("200000.00"),
            remarks="metro",
        )
        txn = self.payment_service.process_pay(cmd)
        self.assertEqual(txn.status, TransactionStatus.PENDING)
        self.assertTrue(txn.is_p2m)
        self.assertTrue(txn.merchant_verified)
        self.assertEqual(user.daily_debit_amount, Decimal("200000.00"))

    def test_p2m_verified_merchant_above_cap_fails(self):
        """Verify that a payment of Rs 2,00,000.01 to a verified merchant fails BR-01."""
        user = self.master_data.get_user("siddharth320@paytm")
        user.daily_debit_amount = Decimal("0.00")
        user.counter_date = date(2026, 10, 21)

        cmd = PayCommand(
            line_no=2,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-VER-OVER",
            timestamp=datetime(2026, 10, 21, 9, 30, 0),
            payer_vpa="siddharth320@paytm",
            payee_vpa="bengaluru@icici",
            amount=Decimal("200000.01"),
            remarks="metro",
        )
        txn = self.payment_service.process_pay(cmd)
        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertEqual(txn.rule_ids, "BR-01")

    def test_p2m_verified_merchant_150000_succeeds(self):
        """Verify README line 84 / line 95: Rs 1,50,000 to verified merchant succeeds."""
        user = self.master_data.get_user("siddharth320@paytm")
        user.daily_debit_amount = Decimal("0.00")
        user.counter_date = date(2026, 10, 21)

        cmd = PayCommand(
            line_no=84,
            command_name="PAY",
            raw_line="PAY|TXN-0028|2026-10-21 10:00:00|siddharth320@paytm|bengaluru@icici|150000.00|transfer",
            txn_id="TXN-0028",
            timestamp=datetime(2026, 10, 21, 10, 0, 0),
            payer_vpa="siddharth320@paytm",
            payee_vpa="bengaluru@icici",
            amount=Decimal("150000.00"),
            remarks="transfer",
        )
        txn = self.payment_service.process_pay(cmd)
        self.assertEqual(txn.status, TransactionStatus.PENDING)
        self.assertTrue(txn.is_p2m)
        self.assertTrue(txn.merchant_verified)

    def test_p2m_unverified_merchant_at_cap(self):
        """Verify that payment of exactly Rs 1,00,000 to an unverified merchant succeeds."""
        user = self.master_data.get_user("siddharth320@paytm")
        user.daily_debit_amount = Decimal("0.00")
        user.counter_date = date(2026, 10, 21)

        cmd = PayCommand(
            line_no=85,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-UNVER-1L",
            timestamp=datetime(2026, 10, 21, 10, 2, 0),
            payer_vpa="siddharth320@paytm",
            payee_vpa="nandini@icici",  # Unverified
            amount=Decimal("100000.00"),
            remarks="milk",
        )
        txn = self.payment_service.process_pay(cmd)
        self.assertEqual(txn.status, TransactionStatus.PENDING)
        self.assertTrue(txn.is_p2m)
        self.assertFalse(txn.merchant_verified)

    def test_p2m_unverified_merchant_cap_exceeded(self):
        """Verify README line 86 / line 95: Rs 1,50,000 to an unverified merchant fails BR-01."""
        cmd = PayCommand(
            line_no=86,
            command_name="PAY",
            raw_line="PAY|TXN-0029|2026-10-21 10:05:00|siddharth320@paytm|nandini@icici|150000.00|dinner",
            txn_id="TXN-0029",
            timestamp=datetime(2026, 10, 21, 10, 5, 0),
            payer_vpa="siddharth320@paytm",
            payee_vpa="nandini@icici",  # Unverified
            amount=Decimal("150000.00"),
            remarks="dinner",
        )
        txn = self.payment_service.process_pay(cmd)
        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertEqual(txn.rule_ids, "BR-01")

    # -------------------------------------------------------------------------
    # 7. P2P Payment Limits (BR-01)
    # -------------------------------------------------------------------------

    def test_p2p_exact_cap_boundary(self):
        """Verify that P2P payment of exactly Rs 1,00,000.00 succeeds BR-01 and BR-02."""
        user = self.master_data.get_user("siddharth320@paytm")
        user.daily_debit_amount = Decimal("0.00")
        user.daily_debit_count = 0
        user.counter_date = date(2026, 10, 22)

        cmd = PayCommand(
            line_no=88,
            command_name="PAY",
            raw_line="PAY|TXN-0030|2026-10-22 10:00:00|siddharth320@paytm|lokesh812@oksbi|100000.00|bill",
            txn_id="TXN-0030",
            timestamp=datetime(2026, 10, 22, 10, 0, 0),
            payer_vpa="siddharth320@paytm",
            payee_vpa="lokesh812@oksbi",
            amount=Decimal("100000.00"),
            remarks="bill",
        )
        txn = self.payment_service.process_pay(cmd)
        self.assertEqual(txn.status, TransactionStatus.PENDING)

    def test_p2p_cap_exceeded_by_one_paisa(self):
        """Verify that P2P payment of Rs 1,00,000.01 fails BR-01."""
        user = self.master_data.get_user("siddharth320@paytm")
        user.daily_debit_amount = Decimal("0.00")
        user.counter_date = date(2026, 10, 23)

        cmd = PayCommand(
            line_no=92,
            command_name="PAY",
            raw_line="PAY|TXN-0031|2026-10-23 10:00:00|siddharth320@paytm|lokesh812@oksbi|100000.01|dinner",
            txn_id="TXN-0031",
            timestamp=datetime(2026, 10, 23, 10, 0, 0),
            payer_vpa="siddharth320@paytm",
            payee_vpa="lokesh812@oksbi",
            amount=Decimal("100000.01"),
            remarks="dinner",
        )
        txn = self.payment_service.process_pay(cmd)
        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertEqual(txn.rule_ids, "BR-01")

    # -------------------------------------------------------------------------
    # 8. Validation Ordering & Balance Evaluated Strictly Last (BR-12)
    # -------------------------------------------------------------------------

    def test_validation_ordering_cap_before_balance(self):
        """Verify README line 95/96: Arjun (balance 1,500) attempts 120,000.

        Both limit (BR-01) and balance (BR-12) fail. System MUST report BR-01,
        proving that available balance is evaluated strictly last.
        """
        user = self.master_data.get_user("arjun598@paytm")
        self.assertEqual(user.balance, Decimal("1500.00"))

        cmd = PayCommand(
            line_no=95,
            command_name="PAY",
            raw_line="PAY|TXN-0032|2026-10-24 10:00:00|arjun598@paytm|lokesh812@oksbi|120000.00|rent",
            txn_id="TXN-0032",
            timestamp=datetime(2026, 10, 24, 10, 0, 0),
            payer_vpa="arjun598@paytm",
            payee_vpa="lokesh812@oksbi",
            amount=Decimal("120000.00"),
            remarks="rent",
        )
        txn = self.payment_service.process_pay(cmd)

        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertEqual(txn.rule_ids, "BR-01")
        self.assertIn("BR-01", txn.rule_ids)
        self.assertNotEqual(txn.rule_ids, "BR-12")

    def test_insufficient_balance_when_all_prior_rules_pass(self):
        """Verify that insufficient balance returns BR-12 only when all limits pass."""
        user = self.master_data.get_user("arjun598@paytm")  # balance 1,500
        cmd = PayCommand(
            line_no=1,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-INSUF",
            timestamp=datetime(2026, 10, 25, 10, 0, 0),
            payer_vpa="arjun598@paytm",
            payee_vpa="lokesh812@oksbi",
            amount=Decimal("2000.00"),  # <= 100,000 P2P, <= 100,000 daily, but > 1,500 balance
            remarks="small_breach",
        )
        txn = self.payment_service.process_pay(cmd)
        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertEqual(txn.rule_ids, "BR-12")

    def test_validation_ordering_daily_cap_before_balance(self):
        """Verify that when both daily cumulative limit and balance fail, daily limit (BR-02) is reported."""
        user = self.master_data.get_user("USR-006")  # Siddharth: balance 900,000
        user.daily_debit_amount = Decimal("90000.00")
        user.counter_date = date(2026, 10, 1)

        # Set user balance lower than amount to simulate both failing
        user.balance = Decimal("12000.00")

        # Payment of 15,000: exceeds remaining daily limit (10,000) AND exceeds balance (12,000)
        cmd = PayCommand(
            line_no=1,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-ORD-DAILY",
            timestamp=datetime(2026, 10, 1, 15, 0, 0),
            payer_vpa="siddharth320@paytm",
            payee_vpa="aditya874@oksbi",
            amount=Decimal("15000.00"),
            remarks="order_test",
        )
        txn = self.payment_service.process_pay(cmd)
        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertEqual(txn.rule_ids, "BR-02")
        self.assertNotEqual(txn.rule_ids, "BR-12")

    def test_validation_ordering_cooling_before_balance(self):
        """Verify that when both cooling cap and balance fail, cooling (BR-04) is reported."""
        user = self.master_data.get_user("USR-003")  # Vidya: vpa_created_at = 2026-10-03 10:01:00
        user.balance = Decimal("2000.00")  # Less than payment amount

        # Payment of 6,000 within 24h: exceeds cooling cap (5,000) AND exceeds balance (2,000)
        cmd = PayCommand(
            line_no=1,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-ORD-COOL",
            timestamp=datetime(2026, 10, 4, 10, 0, 0),  # Within 24h
            payer_vpa="vidya880@okaxis",
            payee_vpa="aditya874@oksbi",
            amount=Decimal("6000.00"),
            remarks="cooling_order",
        )
        txn = self.payment_service.process_pay(cmd)
        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertEqual(txn.rule_ids, "BR-04")
        self.assertNotEqual(txn.rule_ids, "BR-12")

    def test_failed_transactions_do_not_increment_counters(self):
        """Verify that rejected payments do NOT increment daily debit count or amount."""
        user = self.master_data.get_user("USR-001")
        init_amount = user.daily_debit_amount
        init_count = user.daily_debit_count

        # Attempt that breaches daily amount cap
        cmd = PayCommand(
            line_no=1,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-FAIL-COUNT",
            timestamp=datetime(2026, 10, 1, 15, 0, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="aditya874@oksbi",
            amount=Decimal("50000.00"),
            remarks="overshoot",
        )
        txn = self.payment_service.process_pay(cmd)
        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertEqual(user.daily_debit_amount, init_amount)
        self.assertEqual(user.daily_debit_count, init_count)


if __name__ == "__main__":
    unittest.main()
