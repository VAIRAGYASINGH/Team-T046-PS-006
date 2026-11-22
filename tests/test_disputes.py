"""Unit and integration test suite for Phase 6: Refunds, Disputes, Chargebacks, and SLAs."""

from datetime import date, datetime, timedelta
from decimal import Decimal
import unittest

from src.calendar_service import BusinessCalendarService
from src.data import MasterData, load_master_data
from src.disputes import DisputeService, RefundService
from src.ledger import DoubleEntryLedger, LegType
from src.models import (
    DisputeAction,
    DisputeActionCommand,
    DisputeCommand,
    DisputeResultState,
    DisputeType,
    Holiday,
    Merchant,
    PayCommand,
    RefundCommand,
    Transaction,
    TransactionStatus,
    User,
)
from src.payments import PaymentService
from src.rrn_generator import RRNGenerator


class TestPhase6RefundsAndDisputes(unittest.TestCase):
    """Comprehensive test suite for Phase 6 covering all 35 mandated test scenarios."""

    def setUp(self):
        """Set up isolated in-memory test environment."""
        self.master_data = load_master_data()
        self.ledger = DoubleEntryLedger()
        self.rrn_gen = RRNGenerator(start_seq=1)
        self.payment_service = PaymentService(self.master_data, self.ledger, self.rrn_gen)
        self.calendar_service = self.payment_service.calendar_service
        self.dispute_service = self.payment_service.dispute_service
        self.refund_service = self.payment_service.refund_service

        # Helper: Create and confirm a successful P2M transaction
        self.user_vpa = "anjali374@paytm"
        self.merchant_vpa = "udupi@icici"
        self.user = self.master_data.users_by_vpa[self.user_vpa]
        self.merchant = self.master_data.merchants_by_vpa[self.merchant_vpa]

    def _create_p2m_txn(
        self,
        txn_id: str = "TXN-TEST-01",
        amount: Decimal = Decimal("1000.00"),
        timestamp: datetime = datetime(2026, 10, 16, 18, 0, 0),
    ) -> Transaction:
        """Create and settle a valid P2M payment to SUCCESS."""
        pay_cmd = PayCommand(
            line_no=1,
            command_name="PAY",
            raw_line=f"PAY|{txn_id}|{timestamp}|{self.user_vpa}|{self.merchant_vpa}|{amount}|dinner",
            txn_id=txn_id,
            timestamp=timestamp,
            payer_vpa=self.user_vpa,
            payee_vpa=self.merchant_vpa,
            amount=amount,
            remarks="dinner",
        )
        self.payment_service.process_pay(pay_cmd)
        self.payment_service.settle_payment(txn_id, timestamp + timedelta(seconds=2))
        return self.payment_service.get_transaction(txn_id)

    # ---------------------------------------------------------------------------
    # Tests 1 - 9: Refunds
    # ---------------------------------------------------------------------------

    def test_01_valid_full_refund(self):
        """Test 1: Valid full refund."""
        txn = self._create_p2m_txn("TXN-RF-01", Decimal("1000.00"))
        initial_user_bal = self.user.balance
        initial_merchant_bal = self.merchant.balance

        cmd = RefundCommand(line_no=10, command_name="REFUND", raw_line="", txn_id="TXN-RF-01", timestamp=datetime(2026, 10, 17, 10, 0, 0), amount=Decimal("1000.00"))
        res = self.payment_service.process_refund(cmd)

        self.assertTrue(res.is_success)
        self.assertEqual(res.status, "SUCCESS")
        self.assertEqual(txn.refunded_amount, Decimal("1000.00"))
        self.assertEqual(self.user.balance, initial_user_bal + Decimal("1000.00"))
        self.assertEqual(self.merchant.balance, initial_merchant_bal - Decimal("1000.00"))

        # Verify ledger entries
        entries = self.ledger.get_entries_for_txn("TXN-RF-01")
        refund_entries = [e for e in entries if e.leg_type == LegType.REFUND]
        self.assertEqual(len(refund_entries), 2)
        debit_leg = next(e for e in refund_entries if e.vpa == self.merchant_vpa)
        credit_leg = next(e for e in refund_entries if e.vpa == self.user_vpa)
        self.assertEqual(debit_leg.debit, Decimal("1000.00"))
        self.assertEqual(credit_leg.credit, Decimal("1000.00"))

    def test_02_valid_partial_refund(self):
        """Test 2: Valid partial refund."""
        txn = self._create_p2m_txn("TXN-RF-02", Decimal("1000.00"))
        initial_merchant_bal = self.merchant.balance
        initial_user_bal = self.user.balance

        cmd = RefundCommand(line_no=11, command_name="REFUND", raw_line="", txn_id="TXN-RF-02", timestamp=datetime(2026, 10, 17, 10, 0, 0), amount=Decimal("300.00"))
        res = self.payment_service.process_refund(cmd)

        self.assertTrue(res.is_success)
        self.assertEqual(res.status, "SUCCESS")
        self.assertEqual(txn.refunded_amount, Decimal("300.00"))
        self.assertEqual(self.user.balance, initial_user_bal + Decimal("300.00"))
        self.assertEqual(self.merchant.balance, initial_merchant_bal - Decimal("300.00"))

    def test_03_multiple_partial_refunds(self):
        """Test 3: Multiple partial refunds."""
        txn = self._create_p2m_txn("TXN-RF-03", Decimal("1000.00"))

        cmd1 = RefundCommand(line_no=12, command_name="REFUND", raw_line="", txn_id="TXN-RF-03", timestamp=datetime(2026, 10, 17, 10, 0, 0), amount=Decimal("300.00"))
        res1 = self.payment_service.process_refund(cmd1)
        self.assertTrue(res1.is_success)
        self.assertEqual(txn.refunded_amount, Decimal("300.00"))

        cmd2 = RefundCommand(line_no=13, command_name="REFUND", raw_line="", txn_id="TXN-RF-03", timestamp=datetime(2026, 10, 18, 11, 0, 0), amount=Decimal("400.00"))
        res2 = self.payment_service.process_refund(cmd2)
        self.assertTrue(res2.is_success)
        self.assertEqual(txn.refunded_amount, Decimal("700.00"))

        cmd3 = RefundCommand(line_no=14, command_name="REFUND", raw_line="", txn_id="TXN-RF-03", timestamp=datetime(2026, 10, 19, 12, 0, 0), amount=Decimal("300.00"))
        res3 = self.payment_service.process_refund(cmd3)
        self.assertTrue(res3.is_success)
        self.assertEqual(txn.refunded_amount, Decimal("1000.00"))

    def test_04_refund_exceeding_original_amount(self):
        """Test 4: Refund exceeding original amount."""
        self._create_p2m_txn("TXN-RF-04", Decimal("1000.00"))

        cmd = RefundCommand(line_no=15, command_name="REFUND", raw_line="", txn_id="TXN-RF-04", timestamp=datetime(2026, 10, 17, 10, 0, 0), amount=Decimal("1000.01"))
        res = self.payment_service.process_refund(cmd)

        self.assertFalse(res.is_success)
        self.assertEqual(res.status, "FAILED")
        self.assertEqual(res.rule_ids, "REFUND_EXCEEDS_AMOUNT")

    def test_05_refund_exceeding_remaining_refundable_amount(self):
        """Test 5: Refund exceeding remaining refundable amount."""
        txn = self._create_p2m_txn("TXN-RF-05", Decimal("1000.00"))

        # Refund 600.00 first
        self.payment_service.process_refund(
            RefundCommand(line_no=16, command_name="REFUND", raw_line="", txn_id="TXN-RF-05", timestamp=datetime(2026, 10, 17, 10, 0, 0), amount=Decimal("600.00"))
        )
        self.assertEqual(txn.refunded_amount, Decimal("600.00"))

        # Attempt to refund 400.01 (remaining is 400.00)
        res = self.payment_service.process_refund(
            RefundCommand(line_no=17, command_name="REFUND", raw_line="", txn_id="TXN-RF-05", timestamp=datetime(2026, 10, 18, 10, 0, 0), amount=Decimal("400.01"))
        )
        self.assertFalse(res.is_success)
        self.assertEqual(res.status, "FAILED")
        self.assertEqual(res.rule_ids, "REFUND_EXCEEDS_AMOUNT")
        self.assertEqual(txn.refunded_amount, Decimal("600.00"))  # Unchanged

    def test_06_refund_of_reversed_transaction(self):
        """Test 6: Refund of reversed transaction."""
        txn = self._create_p2m_txn("TXN-RF-06", Decimal("500.00"))
        # Force status to REVERSED
        txn.status = TransactionStatus.REVERSED

        cmd = RefundCommand(line_no=18, command_name="REFUND", raw_line="", txn_id="TXN-RF-06", timestamp=datetime(2026, 10, 17, 10, 0, 0), amount=Decimal("500.00"))
        res = self.payment_service.process_refund(cmd)

        self.assertFalse(res.is_success)
        self.assertEqual(res.status, "FAILED")
        self.assertEqual(res.rule_ids, "INVALID_ORIG_TXN")

    def test_07_refund_of_unknown_transaction(self):
        """Test 7: Refund of unknown transaction."""
        cmd = RefundCommand(line_no=19, command_name="REFUND", raw_line="", txn_id="TXN-NONEXISTENT", timestamp=datetime(2026, 10, 17, 10, 0, 0), amount=Decimal("500.00"))
        res = self.payment_service.process_refund(cmd)

        self.assertFalse(res.is_success)
        self.assertEqual(res.status, "FAILED")
        self.assertEqual(res.rule_ids, "INVALID_ORIG_TXN")

    def test_08_zero_and_negative_refund_amounts(self):
        """Test 8: Zero and negative refund amounts."""
        self._create_p2m_txn("TXN-RF-08", Decimal("1000.00"))

        res_zero = self.payment_service.process_refund(
            RefundCommand(line_no=20, command_name="REFUND", raw_line="", txn_id="TXN-RF-08", timestamp=datetime(2026, 10, 17, 10, 0, 0), amount=Decimal("0.00"))
        )
        self.assertFalse(res_zero.is_success)
        self.assertEqual(res_zero.rule_ids, "INVALID_AMOUNT")

        res_neg = self.payment_service.process_refund(
            RefundCommand(line_no=21, command_name="REFUND", raw_line="", txn_id="TXN-RF-08", timestamp=datetime(2026, 10, 17, 10, 0, 0), amount=Decimal("-100.00"))
        )
        self.assertFalse(res_neg.is_success)
        self.assertEqual(res_neg.rule_ids, "INVALID_AMOUNT")

    def test_09_correct_cumulative_refund_tracking(self):
        """Test 9: Correct cumulative refund tracking."""
        txn = self._create_p2m_txn("TXN-RF-09", Decimal("2000.00"))

        amounts = [Decimal("100.00"), Decimal("250.00"), Decimal("400.00"), Decimal("50.00")]
        cumulative = Decimal("0.00")
        for i, amt in enumerate(amounts):
            res = self.payment_service.process_refund(
                RefundCommand(line_no=30 + i, command_name="REFUND", raw_line="", txn_id="TXN-RF-09", timestamp=datetime(2026, 10, 17 + i, 10, 0, 0), amount=amt)
            )
            self.assertTrue(res.is_success)
            cumulative += amt
            self.assertEqual(txn.refunded_amount, cumulative)

    # ---------------------------------------------------------------------------
    # Tests 10 - 17: Disputes
    # ---------------------------------------------------------------------------

    def test_10_valid_dispute_creation(self):
        """Test 10: Valid dispute creation."""
        self._create_p2m_txn("TXN-DSP-10", Decimal("1000.00"), datetime(2026, 10, 16, 18, 0, 0))

        cmd = DisputeCommand(
            line_no=40, command_name="DISPUTE", raw_line="", dispute_id="DSP-10",
            timestamp=datetime(2026, 10, 20, 11, 5, 0), txn_id="TXN-DSP-10",
            dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("1000.00")
        )
        row = self.payment_service.process_dispute(cmd)

        self.assertEqual(row.result_state, "OPEN_CHARGEBACK")
        self.assertIsNone(row.rule_ids)
        self.assertIsNotNone(row.deadline)

    def test_11_dispute_on_reversed_transaction(self):
        """Test 11: Dispute on reversed transaction."""
        txn = self._create_p2m_txn("TXN-DSP-11", Decimal("500.00"))
        txn.status = TransactionStatus.REVERSED

        cmd = DisputeCommand(
            line_no=41, command_name="DISPUTE", raw_line="", dispute_id="DSP-11",
            timestamp=datetime(2026, 10, 20, 11, 0, 0), txn_id="TXN-DSP-11",
            dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("500.00")
        )
        row = self.payment_service.process_dispute(cmd)

        self.assertEqual(row.result_state, "REJECT")
        self.assertEqual(row.rule_ids, "BR-11")

    def test_12_dispute_on_unknown_transaction(self):
        """Test 12: Dispute on unknown transaction."""
        cmd = DisputeCommand(
            line_no=42, command_name="DISPUTE", raw_line="", dispute_id="DSP-12",
            timestamp=datetime(2026, 10, 20, 11, 0, 0), txn_id="TXN-UNKNOWN-99",
            dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("500.00")
        )
        row = self.payment_service.process_dispute(cmd)

        self.assertEqual(row.result_state, "REJECT")
        self.assertEqual(row.rule_ids, "INVALID_TXN")

    def test_13_duplicate_dispute_id(self):
        """Test 13: Duplicate dispute ID."""
        self._create_p2m_txn("TXN-DSP-13", Decimal("1000.00"))

        cmd1 = DisputeCommand(
            line_no=43, command_name="DISPUTE", raw_line="", dispute_id="DSP-13",
            timestamp=datetime(2026, 10, 20, 11, 0, 0), txn_id="TXN-DSP-13",
            dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("400.00")
        )
        row1 = self.payment_service.process_dispute(cmd1)
        self.assertEqual(row1.result_state, "OPEN_CHARGEBACK")

        # Duplicate ID
        cmd2 = DisputeCommand(
            line_no=44, command_name="DISPUTE", raw_line="", dispute_id="DSP-13",
            timestamp=datetime(2026, 10, 20, 11, 5, 0), txn_id="TXN-DSP-13",
            dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("400.00")
        )
        row2 = self.payment_service.process_dispute(cmd2)
        self.assertEqual(row2.result_state, "REJECT")
        self.assertEqual(row2.rule_ids, "DUPLICATE_DISPUTE_ID")

    def test_14_invalid_dispute_type(self):
        """Test 14: Invalid dispute type."""
        self._create_p2m_txn("TXN-DSP-14", Decimal("1000.00"))

        row = self.dispute_service.raise_dispute(
            line_no=45, dispute_id="DSP-14", timestamp=datetime(2026, 10, 20, 11, 0, 0),
            txn_id="TXN-DSP-14", dispute_type="INVALID_TYPE", raised_by="REMITTER", amount=Decimal("500.00")
        )
        self.assertEqual(row.result_state, "REJECT")
        self.assertEqual(row.rule_ids, "INVALID_DISPUTE_TYPE")

    def test_15_invalid_raised_by_value(self):
        """Test 15: Invalid raised_by value."""
        self._create_p2m_txn("TXN-DSP-15", Decimal("1000.00"))

        # Chargeback raised by BENEFICIARY_BANK is invalid
        cmd_cb = DisputeCommand(
            line_no=46, command_name="DISPUTE", raw_line="", dispute_id="DSP-15A",
            timestamp=datetime(2026, 10, 20, 11, 0, 0), txn_id="TXN-DSP-15",
            dispute_type=DisputeType.CHARGEBACK, raised_by="BENEFICIARY_BANK", amount=Decimal("500.00")
        )
        row_cb = self.payment_service.process_dispute(cmd_cb)
        self.assertEqual(row_cb.result_state, "REJECT")
        self.assertEqual(row_cb.rule_ids, "BR-09")

        # TCC raised by REMITTER is invalid
        cmd_tcc = DisputeCommand(
            line_no=47, command_name="DISPUTE", raw_line="", dispute_id="DSP-15B",
            timestamp=datetime(2026, 10, 18, 11, 0, 0), txn_id="TXN-DSP-15",
            dispute_type=DisputeType.TCC, raised_by="REMITTER", amount=Decimal("500.00")
        )
        row_tcc = self.payment_service.process_dispute(cmd_tcc)
        self.assertEqual(row_tcc.result_state, "REJECT")
        self.assertEqual(row_tcc.rule_ids, "BR-08")

    def test_16_invalid_dispute_amount(self):
        """Test 16: Invalid dispute amount."""
        self._create_p2m_txn("TXN-DSP-16", Decimal("1000.00"))

        cmd_zero = DisputeCommand(
            line_no=48, command_name="DISPUTE", raw_line="", dispute_id="DSP-16A",
            timestamp=datetime(2026, 10, 20, 11, 0, 0), txn_id="TXN-DSP-16",
            dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("0.00")
        )
        row_zero = self.payment_service.process_dispute(cmd_zero)
        self.assertEqual(row_zero.result_state, "REJECT")
        self.assertEqual(row_zero.rule_ids, "BR-09")

        cmd_neg = DisputeCommand(
            line_no=49, command_name="DISPUTE", raw_line="", dispute_id="DSP-16B",
            timestamp=datetime(2026, 10, 20, 11, 0, 0), txn_id="TXN-DSP-16",
            dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("-50.00")
        )
        row_neg = self.payment_service.process_dispute(cmd_neg)
        self.assertEqual(row_neg.result_state, "REJECT")
        self.assertEqual(row_neg.rule_ids, "BR-09")

    def test_17_dispute_after_chargeback_window(self):
        """Test 17: Dispute after chargeback window."""
        self._create_p2m_txn("TXN-DSP-17", Decimal("1000.00"), datetime(2026, 10, 1, 10, 0, 0))

        # T+46 is 2026-11-16
        cmd = DisputeCommand(
            line_no=50, command_name="DISPUTE", raw_line="", dispute_id="DSP-17",
            timestamp=datetime(2026, 11, 17, 10, 0, 0), txn_id="TXN-DSP-17",
            dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("1000.00")
        )
        row = self.payment_service.process_dispute(cmd)
        self.assertEqual(row.result_state, "REJECT")
        self.assertEqual(row.rule_ids, "BR-09")

    # ---------------------------------------------------------------------------
    # Tests 18 - 23: TCC and Chargebacks
    # ---------------------------------------------------------------------------

    def test_18_chargeback_on_t_plus_45(self):
        """Test 18: Chargeback on T+45 (Allowed)."""
        self._create_p2m_txn("TXN-CB-45", Decimal("3200.00"), datetime(2026, 10, 2, 12, 0, 0))

        # Exactly 45 days after 2026-10-02 is 2026-11-16
        cmd = DisputeCommand(
            line_no=137, command_name="DISPUTE", raw_line="", dispute_id="DSP-002",
            timestamp=datetime(2026, 11, 16, 10, 0, 0), txn_id="TXN-CB-45",
            dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("3200.00")
        )
        row = self.payment_service.process_dispute(cmd)
        self.assertEqual(row.result_state, "OPEN_CHARGEBACK")
        self.assertIsNone(row.rule_ids)

    def test_19_chargeback_on_t_plus_46(self):
        """Test 19: Chargeback on T+46 (Rejected)."""
        self._create_p2m_txn("TXN-CB-46", Decimal("1800.00"), datetime(2026, 10, 2, 13, 0, 0))

        # Exactly 46 days after 2026-10-02 is 2026-11-17
        cmd = DisputeCommand(
            line_no=143, command_name="DISPUTE", raw_line="", dispute_id="DSP-003",
            timestamp=datetime(2026, 11, 17, 10, 0, 0), txn_id="TXN-CB-46",
            dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("1800.00")
        )
        row = self.payment_service.process_dispute(cmd)
        self.assertEqual(row.result_state, "REJECT")
        self.assertEqual(row.rule_ids, "BR-09")

    def test_20_tcc_on_t_plus_5(self):
        """Test 20: TCC on T+5 (Allowed)."""
        self._create_p2m_txn("TXN-TCC-05", Decimal("980.00"), datetime(2026, 10, 13, 9, 0, 0))

        # Exactly 5 calendar days after 2026-10-13 is 2026-10-18
        cmd = DisputeCommand(
            line_no=75, command_name="DISPUTE", raw_line="", dispute_id="DSP-005",
            timestamp=datetime(2026, 10, 18, 16, 0, 0), txn_id="TXN-TCC-05",
            dispute_type=DisputeType.TCC, raised_by="BENEFICIARY_BANK", amount=Decimal("980.00")
        )
        row = self.payment_service.process_dispute(cmd)
        self.assertEqual(row.result_state, "OPEN_TCC")
        self.assertIsNone(row.rule_ids)

    def test_21_tcc_on_t_plus_6(self):
        """Test 21: TCC on T+6 (Rejected)."""
        self._create_p2m_txn("TXN-TCC-06", Decimal("560.00"), datetime(2026, 10, 13, 10, 0, 0))

        # Exactly 6 calendar days after 2026-10-13 is 2026-10-19
        cmd = DisputeCommand(
            line_no=78, command_name="DISPUTE", raw_line="", dispute_id="DSP-007",
            timestamp=datetime(2026, 10, 19, 10, 5, 0), txn_id="TXN-TCC-06",
            dispute_type=DisputeType.TCC, raised_by="BENEFICIARY_BANK", amount=Decimal("560.00")
        )
        row = self.payment_service.process_dispute(cmd)
        self.assertEqual(row.result_state, "REJECT")
        self.assertEqual(row.rule_ids, "BR-08")

    def test_22_open_tcc_blocks_chargeback(self):
        """Test 22: Open TCC blocks chargeback."""
        self._create_p2m_txn("TXN-TCC-BLK", Decimal("980.00"), datetime(2026, 10, 13, 9, 0, 0))

        # Lodge valid TCC
        self.payment_service.process_dispute(
            DisputeCommand(
                line_no=75, command_name="DISPUTE", raw_line="", dispute_id="DSP-TCC-BLK",
                timestamp=datetime(2026, 10, 18, 16, 0, 0), txn_id="TXN-TCC-BLK",
                dispute_type=DisputeType.TCC, raised_by="BENEFICIARY_BANK", amount=Decimal("980.00")
            )
        )

        # Attempt chargeback while TCC is OPEN
        cmd_cb = DisputeCommand(
            line_no=77, command_name="DISPUTE", raw_line="", dispute_id="DSP-CB-BLK",
            timestamp=datetime(2026, 10, 19, 10, 0, 0), txn_id="TXN-TCC-BLK",
            dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("980.00")
        )
        row_cb = self.payment_service.process_dispute(cmd_cb)
        self.assertEqual(row_cb.result_state, "REJECT")
        self.assertEqual(row_cb.rule_ids, "BR-11")

    def test_23_partial_refund_reduces_chargeback_exposure(self):
        """Test 23: Partial refund reduces chargeback exposure."""
        self._create_p2m_txn("TXN-EXP-01", Decimal("1000.00"), datetime(2026, 10, 16, 18, 0, 0))

        # Partial refund of 300.00
        self.payment_service.process_refund(
            RefundCommand(line_no=73, command_name="REFUND", raw_line="", txn_id="TXN-EXP-01", timestamp=datetime(2026, 10, 17, 10, 0, 0), amount=Decimal("300.00"))
        )

        # Chargeback for full 1000.00 must be rejected (max 700.00)
        cmd_full = DisputeCommand(
            line_no=80, command_name="DISPUTE", raw_line="", dispute_id="DSP-009",
            timestamp=datetime(2026, 10, 20, 11, 0, 0), txn_id="TXN-EXP-01",
            dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("1000.00")
        )
        row_full = self.payment_service.process_dispute(cmd_full)
        self.assertEqual(row_full.result_state, "REJECT")
        self.assertEqual(row_full.rule_ids, "BR-09")

        # Chargeback for 700.00 matches remaining exposure and is accepted
        cmd_adj = DisputeCommand(
            line_no=81, command_name="DISPUTE", raw_line="", dispute_id="DSP-010",
            timestamp=datetime(2026, 10, 20, 11, 5, 0), txn_id="TXN-EXP-01",
            dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("700.00")
        )
        row_adj = self.payment_service.process_dispute(cmd_adj)
        self.assertEqual(row_adj.result_state, "OPEN_CHARGEBACK")

    # ---------------------------------------------------------------------------
    # Tests 24 - 30: Representment & SLA Deadlines
    # ---------------------------------------------------------------------------

    def test_24_fifth_working_day_deadline(self):
        """Test 24: Fifth working-day deadline."""
        # Clean week with no holidays: Monday 2026-10-05
        # Day 1: Tue Oct 6, Day 2: Wed Oct 7, Day 3: Thu Oct 8, Day 4: Fri Oct 9, Day 5: Mon Oct 12
        deadline = self.calendar_service.calculate_representment_deadline(date(2026, 10, 5))
        self.assertEqual(deadline, datetime(2026, 10, 12, 23, 59, 59))

    def test_25_weekend_adjustment(self):
        """Test 25: Weekend adjustment (skips Saturday and Sunday)."""
        # Wednesday 2026-10-07
        # Day 1: Thu Oct 8, Day 2: Fri Oct 9, Sat-Sun skipped, Day 3: Mon Oct 12, Day 4: Tue Oct 13, Day 5: Wed Oct 14
        deadline = self.calendar_service.calculate_representment_deadline(date(2026, 10, 7))
        self.assertEqual(deadline, datetime(2026, 10, 14, 23, 59, 59))

    def test_26_holiday_adjustment(self):
        """Test 26: Holiday adjustment (Vijaya Dashami Oct 20)."""
        # Thursday 2026-10-15
        # Day 1: Fri Oct 16, Weekend Oct 17-18 skipped, Day 2: Mon Oct 19,
        # Tue Oct 20 (Holiday) skipped, Day 3: Wed Oct 21, Day 4: Thu Oct 22, Day 5: Fri Oct 23
        deadline = self.calendar_service.calculate_representment_deadline(date(2026, 10, 15))
        self.assertEqual(deadline, datetime(2026, 10, 23, 23, 59, 59))

    def test_27_2026_11_09_holiday_handling(self):
        """Test 27: 2026-11-09 Deepavali holiday handling."""
        # Monday 2026-11-02 (DSP-004 in commands.txt line 113)
        # Day 1: Tue Nov 3, Day 2: Wed Nov 4, Day 3: Thu Nov 5, Day 4: Fri Nov 6,
        # Weekend Nov 7-8 skipped, Mon Nov 9 (Deepavali) skipped, Day 5: Tue Nov 10
        deadline = self.calendar_service.calculate_representment_deadline(date(2026, 11, 2))
        self.assertEqual(deadline, datetime(2026, 11, 10, 23, 59, 59))

    def test_28_action_before_deadline(self):
        """Test 28: Action before deadline (in time)."""
        self._create_p2m_txn("TXN-REP-28", Decimal("2500.00"), datetime(2026, 10, 28, 10, 0, 0))

        # Lodge dispute Nov 02 (deadline is Nov 10 23:59:59)
        self.payment_service.process_dispute(
            DisputeCommand(
                line_no=113, command_name="DISPUTE", raw_line="", dispute_id="DSP-REP-28",
                timestamp=datetime(2026, 11, 2, 10, 0, 0), txn_id="TXN-REP-28",
                dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("2500.00")
            )
        )

        # Action on Nov 06 (Working day 4, before deadline)
        res = self.payment_service.process_dispute_action(
            DisputeActionCommand(line_no=120, command_name="DISPUTE_ACTION", raw_line="", dispute_id="DSP-REP-28", timestamp=datetime(2026, 11, 6, 11, 0, 0), action=DisputeAction.REPRESENT)
        )
        self.assertEqual(res.result_state, "REPRESENTED")

    def test_29_action_on_deadline(self):
        """Test 29: Action exactly on deadline."""
        self._create_p2m_txn("TXN-REP-29", Decimal("2500.00"), datetime(2026, 10, 28, 10, 0, 0))

        self.payment_service.process_dispute(
            DisputeCommand(
                line_no=113, command_name="DISPUTE", raw_line="", dispute_id="DSP-REP-29",
                timestamp=datetime(2026, 11, 2, 10, 0, 0), txn_id="TXN-REP-29",
                dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("2500.00")
            )
        )

        # Action on Nov 10 11:00:00 (on 5th working day, before 23:59:59)
        res = self.payment_service.process_dispute_action(
            DisputeActionCommand(line_no=126, command_name="DISPUTE_ACTION", raw_line="", dispute_id="DSP-REP-29", timestamp=datetime(2026, 11, 10, 11, 0, 0), action=DisputeAction.REPRESENT)
        )
        self.assertEqual(res.result_state, "REPRESENTED")
        self.assertEqual(res.deadline, datetime(2026, 11, 17, 23, 59, 59))  # Pre-arbitration deadline: 7 calendar days

    def test_30_action_after_deadline(self):
        """Test 30: Action after deadline (rejected under BR-10)."""
        self._create_p2m_txn("TXN-REP-30", Decimal("1300.00"), datetime(2026, 10, 14, 12, 0, 0))

        # Lodge Oct 15 -> deadline Oct 23 23:59:59
        self.payment_service.process_dispute(
            DisputeCommand(
                line_no=68, command_name="DISPUTE", raw_line="", dispute_id="DSP-REP-30",
                timestamp=datetime(2026, 10, 15, 9, 0, 0), txn_id="TXN-REP-30",
                dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("1300.00")
            )
        )

        # Action on Oct 27 (past deadline)
        res = self.payment_service.process_dispute_action(
            DisputeActionCommand(line_no=104, command_name="DISPUTE_ACTION", raw_line="", dispute_id="DSP-REP-30", timestamp=datetime(2026, 10, 27, 9, 0, 0), action=DisputeAction.REPRESENT)
        )
        self.assertEqual(res.result_state, "REJECT")
        self.assertEqual(res.rule_ids, "BR-10")

    # ---------------------------------------------------------------------------
    # Tests 31 - 35: State Transitions
    # ---------------------------------------------------------------------------

    def test_31_valid_dispute_action_lifecycle(self):
        """Test 31: Valid dispute action (ACCEPT, PRE_ARBITRATION, CLOSE)."""
        self._create_p2m_txn("TXN-TR-31", Decimal("2500.00"), datetime(2026, 10, 28, 10, 0, 0))

        # 1. Lodge
        self.payment_service.process_dispute(
            DisputeCommand(
                line_no=113, command_name="DISPUTE", raw_line="", dispute_id="DSP-TR-31",
                timestamp=datetime(2026, 11, 2, 10, 0, 0), txn_id="TXN-TR-31",
                dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("2500.00")
            )
        )

        # 2. Represent
        res_rep = self.payment_service.process_dispute_action(
            DisputeActionCommand(line_no=126, command_name="DISPUTE_ACTION", raw_line="", dispute_id="DSP-TR-31", timestamp=datetime(2026, 11, 10, 11, 0, 0), action=DisputeAction.REPRESENT)
        )
        self.assertEqual(res_rep.result_state, "REPRESENTED")

        # 3. Pre-Arbitration (within 7 calendar days on Nov 17)
        res_pre = self.payment_service.process_dispute_action(
            DisputeActionCommand(line_no=144, command_name="DISPUTE_ACTION", raw_line="", dispute_id="DSP-TR-31", timestamp=datetime(2026, 11, 17, 11, 0, 0), action=DisputeAction.PRE_ARBITRATION)
        )
        self.assertEqual(res_pre.result_state, "PRE_ARBITRATION")

        # 4. Close
        res_close = self.payment_service.process_dispute_action(
            DisputeActionCommand(line_no=146, command_name="DISPUTE_ACTION", raw_line="", dispute_id="DSP-TR-31", timestamp=datetime(2026, 11, 19, 9, 0, 0), action=DisputeAction.CLOSE)
        )
        self.assertEqual(res_close.result_state, "CLOSED")

    def test_32_invalid_dispute_action(self):
        """Test 32: Invalid dispute action (e.g. REPRESENT after ACCEPT)."""
        self._create_p2m_txn("TXN-TR-32", Decimal("700.00"), datetime(2026, 10, 16, 18, 0, 0))

        self.payment_service.process_dispute(
            DisputeCommand(
                line_no=81, command_name="DISPUTE", raw_line="", dispute_id="DSP-TR-32",
                timestamp=datetime(2026, 10, 20, 11, 5, 0), txn_id="TXN-TR-32",
                dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("700.00")
            )
        )

        # Accept
        self.payment_service.process_dispute_action(
            DisputeActionCommand(line_no=90, command_name="DISPUTE_ACTION", raw_line="", dispute_id="DSP-TR-32", timestamp=datetime(2026, 10, 22, 11, 0, 0), action=DisputeAction.ACCEPT)
        )

        # Attempt to REPRESENT after ACCEPT
        res_illegal = self.payment_service.process_dispute_action(
            DisputeActionCommand(line_no=93, command_name="DISPUTE_ACTION", raw_line="", dispute_id="DSP-TR-32", timestamp=datetime(2026, 10, 23, 11, 0, 0), action=DisputeAction.REPRESENT)
        )
        self.assertEqual(res_illegal.result_state, "REJECT")
        self.assertEqual(res_illegal.rule_ids, "BR-10")

    def test_33_no_action_automatic_acceptance(self):
        """Test 33: No-action automatic acceptance."""
        self._create_p2m_txn("TXN-TR-33", Decimal("1300.00"), datetime(2026, 10, 14, 12, 0, 0))
        initial_user_bal = self.user.balance
        initial_merchant_bal = self.merchant.balance

        # Lodge Oct 15 -> deadline Oct 23 23:59:59
        self.payment_service.process_dispute(
            DisputeCommand(
                line_no=68, command_name="DISPUTE", raw_line="", dispute_id="DSP-TR-33",
                timestamp=datetime(2026, 10, 15, 9, 0, 0), txn_id="TXN-TR-33",
                dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("1300.00")
            )
        )

        # Sweep on Oct 25 (past deadline)
        swept = self.dispute_service.sweep_auto_acceptance(datetime(2026, 10, 25, 0, 0, 0))
        self.assertEqual(len(swept), 1)
        self.assertEqual(swept[0].status, DisputeResultState.AUTO_ACCEPTED)

        # Verify financial settlement
        self.assertEqual(self.user.balance, initial_user_bal + Decimal("1300.00"))
        self.assertEqual(self.merchant.balance, initial_merchant_bal - Decimal("1300.00"))

    def test_34_duplicate_action(self):
        """Test 34: Duplicate action (e.g. REPRESENT twice)."""
        self._create_p2m_txn("TXN-TR-34", Decimal("2500.00"), datetime(2026, 10, 28, 10, 0, 0))

        self.payment_service.process_dispute(
            DisputeCommand(
                line_no=113, command_name="DISPUTE", raw_line="", dispute_id="DSP-TR-34",
                timestamp=datetime(2026, 11, 2, 10, 0, 0), txn_id="TXN-TR-34",
                dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("2500.00")
            )
        )

        # First represent
        res1 = self.payment_service.process_dispute_action(
            DisputeActionCommand(line_no=126, command_name="DISPUTE_ACTION", raw_line="", dispute_id="DSP-TR-34", timestamp=datetime(2026, 11, 10, 11, 0, 0), action=DisputeAction.REPRESENT)
        )
        self.assertEqual(res1.result_state, "REPRESENTED")

        # Second represent (duplicate)
        res2 = self.payment_service.process_dispute_action(
            DisputeActionCommand(line_no=127, command_name="DISPUTE_ACTION", raw_line="", dispute_id="DSP-TR-34", timestamp=datetime(2026, 11, 10, 12, 0, 0), action=DisputeAction.REPRESENT)
        )
        self.assertEqual(res2.result_state, "REJECT")
        self.assertEqual(res2.rule_ids, "BR-10")

    def test_35_action_on_unknown_dispute(self):
        """Test 35: Action on unknown dispute."""
        res = self.payment_service.process_dispute_action(
            DisputeActionCommand(line_no=200, command_name="DISPUTE_ACTION", raw_line="", dispute_id="DSP-UNKNOWN-99", timestamp=datetime(2026, 11, 10, 11, 0, 0), action=DisputeAction.ACCEPT)
        )
        self.assertEqual(res.result_state, "REJECT")
        self.assertEqual(res.rule_ids, "INVALID_DISPUTE")

    def test_accounting_consistency_conservation_invariant(self):
        """Verify total system balance conservation across all operations."""
        initial_total = self.ledger.total_system_balance(
            list(self.master_data.users_by_vpa.values()),
            list(self.master_data.merchants_by_vpa.values()),
        )

        self._create_p2m_txn("TXN-CONS-01", Decimal("5000.00"))
        self.payment_service.process_refund(
            RefundCommand(line_no=1, command_name="REFUND", raw_line="", txn_id="TXN-CONS-01", timestamp=datetime(2026, 10, 17, 10, 0, 0), amount=Decimal("1500.00"))
        )
        self.payment_service.process_dispute(
            DisputeCommand(
                line_no=2, command_name="DISPUTE", raw_line="", dispute_id="DSP-CONS-01",
                timestamp=datetime(2026, 10, 20, 11, 0, 0), txn_id="TXN-CONS-01",
                dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("2000.00")
            )
        )
        self.payment_service.process_dispute_action(
            DisputeActionCommand(line_no=3, command_name="DISPUTE_ACTION", raw_line="", dispute_id="DSP-CONS-01", timestamp=datetime(2026, 10, 21, 11, 0, 0), action=DisputeAction.ACCEPT)
        )

        current_total = self.ledger.total_system_balance(
            list(self.master_data.users_by_vpa.values()),
            list(self.master_data.merchants_by_vpa.values()),
        )
        self.assertEqual(initial_total, current_total)


if __name__ == "__main__":
    unittest.main()
