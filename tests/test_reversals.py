"""Comprehensive unit and integration tests for Phase 4: Beneficiary Responses, Timeouts & Reversals."""

import unittest
from datetime import date, datetime, timedelta
from decimal import Decimal

from src.data import load_master_data
from src.ledger import DoubleEntryLedger
from src.models import (
    BeneResponseCommand,
    BeneResponseType,
    PayCommand,
    TimeoutJobCommand,
    TransactionStatus,
)
from src.payments import PaymentService
from src.reversals import StateMachineError, TransactionStateMachine


class TestReversalsAndLifecycle(unittest.TestCase):
    """Test suite covering BR-05 (Timeouts), BR-06 (Late ACK/Shortfall), and State Machine."""

    def setUp(self):
        self.master_data = load_master_data()
        self.ledger = DoubleEntryLedger()
        self.service = PaymentService(
            master_data=self.master_data,
            ledger=self.ledger,
        )

    # -----------------------------------------------------------------------
    # 1. Beneficiary Responses (ACK, NACK, NO_RESPONSE)
    # -----------------------------------------------------------------------

    def test_bene_ack_settles_payment(self):
        """Standard ACK settles payment to beneficiary and updates ledger."""
        pay_cmd = PayCommand(
            line_no=3,
            command_name="PAY",
            raw_line="PAY|TXN-T1|2026-10-01 09:10:00|rohit890@okaxis|keerthi335@okaxis|5000.00|rent",
            txn_id="TXN-T1",
            timestamp=datetime(2026, 10, 1, 9, 10, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="keerthi335@okaxis",
            amount=Decimal("5000.00"),
            remarks="rent",
        )
        txn = self.service.process_pay(pay_cmd)
        self.assertEqual(txn.status, TransactionStatus.PENDING)
        self.assertEqual(self.ledger.clearing_balance, Decimal("5000.00"))

        payee = self.master_data.users_by_vpa["keerthi335@okaxis"]
        payee_initial_balance = payee.balance

        # ACK arrives
        ack_cmd = BeneResponseCommand(
            line_no=4,
            command_name="BENE_RESPONSE",
            raw_line="BENE_RESPONSE|TXN-T1|2026-10-01 09:10:03|ACK",
            txn_id="TXN-T1",
            timestamp=datetime(2026, 10, 1, 9, 10, 3),
            response=BeneResponseType.ACK,
        )
        res = self.service.process_bene_response(ack_cmd)

        self.assertTrue(res.is_success)
        self.assertEqual(res.action_taken, "SETTLED")
        self.assertEqual(txn.status, TransactionStatus.SUCCESS)
        self.assertEqual(payee.balance, payee_initial_balance + Decimal("5000.00"))
        self.assertEqual(self.ledger.clearing_balance, Decimal("0.00"))

    def test_bene_nack_refunds_payment_and_restores_counters(self):
        """Standard NACK marks transaction FAILED, refunds payer, and restores counters."""
        payer = self.master_data.users_by_vpa["rohit890@okaxis"]
        initial_balance = payer.balance
        initial_amount = payer.daily_debit_amount
        initial_count = payer.daily_debit_count

        pay_cmd = PayCommand(
            line_no=5,
            command_name="PAY",
            raw_line="PAY|TXN-T2|2026-10-01 09:15:00|rohit890@okaxis|keerthi335@okaxis|500.00|test",
            txn_id="TXN-T2",
            timestamp=datetime(2026, 10, 1, 9, 15, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="keerthi335@okaxis",
            amount=Decimal("500.00"),
            remarks="test",
        )
        txn = self.service.process_pay(pay_cmd)
        self.assertEqual(payer.balance, initial_balance - Decimal("500.00"))
        self.assertEqual(payer.daily_debit_amount, initial_amount + Decimal("500.00"))
        self.assertEqual(payer.daily_debit_count, initial_count + 1)

        # NACK arrives
        nack_cmd = BeneResponseCommand(
            line_no=6,
            command_name="BENE_RESPONSE",
            raw_line="BENE_RESPONSE|TXN-T2|2026-10-01 09:15:04|NACK",
            txn_id="TXN-T2",
            timestamp=datetime(2026, 10, 1, 9, 15, 4),
            response=BeneResponseType.NACK,
        )
        res = self.service.process_bene_response(nack_cmd)

        self.assertTrue(res.is_success)
        self.assertEqual(res.action_taken, "REFUNDED_NACK")
        self.assertEqual(txn.status, TransactionStatus.FAILED)
        self.assertEqual(payer.balance, initial_balance)
        self.assertEqual(payer.daily_debit_amount, initial_amount)
        self.assertEqual(payer.daily_debit_count, initial_count)
        self.assertEqual(self.ledger.clearing_balance, Decimal("0.00"))

    def test_bene_no_response_retains_pending(self):
        """NO_RESPONSE retains PENDING state and executes zero ledger movements."""
        pay_cmd = PayCommand(
            line_no=7,
            command_name="PAY",
            raw_line="PAY|TXN-T3|2026-10-01 09:20:00|rohit890@okaxis|keerthi335@okaxis|1000.00|test",
            txn_id="TXN-T3",
            timestamp=datetime(2026, 10, 1, 9, 20, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="keerthi335@okaxis",
            amount=Decimal("1000.00"),
            remarks="test",
        )
        txn = self.service.process_pay(pay_cmd)

        nr_cmd = BeneResponseCommand(
            line_no=8,
            command_name="BENE_RESPONSE",
            raw_line="BENE_RESPONSE|TXN-T3|2026-10-01 09:20:05|NO_RESPONSE",
            txn_id="TXN-T3",
            timestamp=datetime(2026, 10, 1, 9, 20, 5),
            response=BeneResponseType.NO_RESPONSE,
        )
        res = self.service.process_bene_response(nr_cmd)

        self.assertTrue(res.is_success)
        self.assertEqual(res.action_taken, "NO_OP_PENDING")
        self.assertEqual(txn.status, TransactionStatus.PENDING)
        self.assertEqual(self.ledger.clearing_balance, Decimal("1000.00"))

    def test_out_of_order_packet_unknown_txn_id(self):
        """BENE_RESPONSE arriving for non-existent txn logs OUT_OF_ORDER_PACKET without crashing."""
        cmd = BeneResponseCommand(
            line_no=115,
            command_name="BENE_RESPONSE",
            raw_line="BENE_RESPONSE|TXN-NONEXISTENT|2026-11-05 10:20:06|ACK",
            txn_id="TXN-NONEXISTENT",
            timestamp=datetime(2026, 11, 5, 10, 20, 6),
            response=BeneResponseType.ACK,
        )
        res = self.service.process_bene_response(cmd)

        self.assertFalse(res.is_success)
        self.assertEqual(res.action_taken, "IGNORED_OUT_OF_ORDER")
        records = self.service.get_reconciliation_records()
        self.assertEqual(len(records), 1)
        self.assertEqual(records[0].incident_type, "OUT_OF_ORDER_PACKET")
        self.assertEqual(records[0].txn_id, "TXN-NONEXISTENT")

    def test_duplicate_bene_ack_on_success(self):
        """Duplicate ACK on SUCCESS is an idempotent no-op with zero double credit."""
        pay_cmd = PayCommand(
            line_no=10,
            command_name="PAY",
            raw_line="PAY|TXN-T4|2026-10-01 10:00:00|rohit890@okaxis|keerthi335@okaxis|2000.00|test",
            txn_id="TXN-T4",
            timestamp=datetime(2026, 10, 1, 10, 0, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="keerthi335@okaxis",
            amount=Decimal("2000.00"),
            remarks="test",
        )
        txn = self.service.process_pay(pay_cmd)

        ack_cmd = BeneResponseCommand(
            line_no=11,
            command_name="BENE_RESPONSE",
            raw_line="BENE_RESPONSE|TXN-T4|2026-10-01 10:00:03|ACK",
            txn_id="TXN-T4",
            timestamp=datetime(2026, 10, 1, 10, 0, 3),
            response=BeneResponseType.ACK,
        )
        self.service.process_bene_response(ack_cmd)
        payee = self.master_data.users_by_vpa["keerthi335@okaxis"]
        balance_after_ack = payee.balance

        # Duplicate ACK arrives
        dup_res = self.service.process_bene_response(ack_cmd)
        self.assertTrue(dup_res.is_success)
        self.assertEqual(dup_res.action_taken, "DUPLICATE_IGNORED")
        self.assertEqual(payee.balance, balance_after_ack)

    def test_duplicate_bene_nack_on_failed(self):
        """Duplicate NACK on FAILED is an idempotent no-op with zero double refund."""
        pay_cmd = PayCommand(
            line_no=12,
            command_name="PAY",
            raw_line="PAY|TXN-T5|2026-10-01 10:05:00|rohit890@okaxis|keerthi335@okaxis|500.00|test",
            txn_id="TXN-T5",
            timestamp=datetime(2026, 10, 1, 10, 5, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="keerthi335@okaxis",
            amount=Decimal("500.00"),
            remarks="test",
        )
        txn = self.service.process_pay(pay_cmd)

        nack_cmd = BeneResponseCommand(
            line_no=13,
            command_name="BENE_RESPONSE",
            raw_line="BENE_RESPONSE|TXN-T5|2026-10-01 10:05:04|NACK",
            txn_id="TXN-T5",
            timestamp=datetime(2026, 10, 1, 10, 5, 4),
            response=BeneResponseType.NACK,
        )
        self.service.process_bene_response(nack_cmd)
        payer = self.master_data.users_by_vpa["rohit890@okaxis"]
        balance_after_nack = payer.balance

        # Duplicate NACK arrives
        dup_res = self.service.process_bene_response(nack_cmd)
        self.assertTrue(dup_res.is_success)
        self.assertEqual(dup_res.action_taken, "DUPLICATE_IGNORED")
        self.assertEqual(payer.balance, balance_after_nack)

    def test_conflicting_nack_on_success_blocked(self):
        """Conflicting NACK received on settled SUCCESS transaction is blocked."""
        pay_cmd = PayCommand(
            line_no=14,
            command_name="PAY",
            raw_line="PAY|TXN-T6|2026-10-01 10:10:00|rohit890@okaxis|keerthi335@okaxis|1500.00|test",
            txn_id="TXN-T6",
            timestamp=datetime(2026, 10, 1, 10, 10, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="keerthi335@okaxis",
            amount=Decimal("1500.00"),
            remarks="test",
        )
        txn = self.service.process_pay(pay_cmd)
        self.service.process_bene_response(
            BeneResponseCommand(15, "BENE_RESPONSE", "", "TXN-T6", datetime(2026, 10, 1, 10, 10, 5), BeneResponseType.ACK)
        )
        self.assertEqual(txn.status, TransactionStatus.SUCCESS)

        # Conflicting NACK arrives
        conflict_res = self.service.process_bene_response(
            BeneResponseCommand(16, "BENE_RESPONSE", "", "TXN-T6", datetime(2026, 10, 1, 10, 10, 10), BeneResponseType.NACK)
        )
        self.assertFalse(conflict_res.is_success)
        self.assertEqual(conflict_res.action_taken, "CONFLICT_BLOCKED")
        self.assertEqual(txn.status, TransactionStatus.SUCCESS)

    def test_conflicting_ack_on_failed_blocked(self):
        """Conflicting ACK received on terminal FAILED transaction is blocked."""
        pay_cmd = PayCommand(
            line_no=17,
            command_name="PAY",
            raw_line="PAY|TXN-T7|2026-10-01 10:15:00|rohit890@okaxis|keerthi335@okaxis|700.00|test",
            txn_id="TXN-T7",
            timestamp=datetime(2026, 10, 1, 10, 15, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="keerthi335@okaxis",
            amount=Decimal("700.00"),
            remarks="test",
        )
        txn = self.service.process_pay(pay_cmd)
        self.service.process_bene_response(
            BeneResponseCommand(18, "BENE_RESPONSE", "", "TXN-T7", datetime(2026, 10, 1, 10, 15, 4), BeneResponseType.NACK)
        )
        self.assertEqual(txn.status, TransactionStatus.FAILED)

        # Conflicting ACK arrives
        conflict_res = self.service.process_bene_response(
            BeneResponseCommand(19, "BENE_RESPONSE", "", "TXN-T7", datetime(2026, 10, 1, 10, 15, 10), BeneResponseType.ACK)
        )
        self.assertFalse(conflict_res.is_success)
        self.assertEqual(conflict_res.action_taken, "CONFLICT_BLOCKED")
        self.assertEqual(txn.status, TransactionStatus.FAILED)

    # -----------------------------------------------------------------------
    # 2. Timeout Sweeps (BR-05)
    # -----------------------------------------------------------------------

    def test_timeout_sweep_before_30_minutes_skipped(self):
        """Transaction elapsed 29 minutes 59 seconds (1,799s) is skipped by TIMEOUT_JOB."""
        pay_cmd = PayCommand(
            line_no=20,
            command_name="PAY",
            raw_line="PAY|TXN-TO1|2026-10-08 10:00:00|aditya874@oksbi|keerthi335@okaxis|5000.00|bill",
            txn_id="TXN-TO1",
            timestamp=datetime(2026, 10, 8, 10, 0, 0),
            payer_vpa="aditya874@oksbi",
            payee_vpa="keerthi335@okaxis",
            amount=Decimal("5000.00"),
            remarks="bill",
        )
        txn = self.service.process_pay(pay_cmd)

        job_cmd = TimeoutJobCommand(
            line_no=21,
            command_name="TIMEOUT_JOB",
            raw_line="TIMEOUT_JOB|2026-10-08 10:29:59",
            timestamp=datetime(2026, 10, 8, 10, 29, 59),  # 1799 seconds
        )
        reversed_list = self.service.process_timeout_job(job_cmd)

        self.assertEqual(len(reversed_list), 0)
        self.assertEqual(txn.status, TransactionStatus.PENDING)
        self.assertIsNone(txn.reversed_at)

    def test_timeout_sweep_exact_30_minutes_reversed(self):
        """Transaction elapsed exactly 30 minutes (1,800s) is auto-reversed by TIMEOUT_JOB."""
        payer = self.master_data.users_by_vpa["aditya874@oksbi"]
        initial_balance = payer.balance

        pay_cmd = PayCommand(
            line_no=22,
            command_name="PAY",
            raw_line="PAY|TXN-TO2|2026-10-08 10:00:00|aditya874@oksbi|keerthi335@okaxis|12000.00|bill",
            txn_id="TXN-TO2",
            timestamp=datetime(2026, 10, 8, 10, 0, 0),
            payer_vpa="aditya874@oksbi",
            payee_vpa="keerthi335@okaxis",
            amount=Decimal("12000.00"),
            remarks="bill",
        )
        txn = self.service.process_pay(pay_cmd)

        job_cmd = TimeoutJobCommand(
            line_no=23,
            command_name="TIMEOUT_JOB",
            raw_line="TIMEOUT_JOB|2026-10-08 10:30:00",
            timestamp=datetime(2026, 10, 8, 10, 30, 0),  # exactly 1800s
        )
        reversed_list = self.service.process_timeout_job(job_cmd)

        self.assertEqual(len(reversed_list), 1)
        self.assertEqual(reversed_list[0].txn_id, "TXN-TO2")
        self.assertEqual(txn.status, TransactionStatus.REVERSED)
        self.assertEqual(txn.reversed_at, datetime(2026, 10, 8, 10, 30, 0))
        self.assertEqual(payer.balance, initial_balance)  # Refunded

    def test_timeout_sweep_after_30_minutes_reversed(self):
        """Transaction elapsed 31 minutes (1,860s) is auto-reversed by TIMEOUT_JOB."""
        pay_cmd = PayCommand(
            line_no=24,
            command_name="PAY",
            raw_line="PAY|TXN-TO3|2026-10-09 15:00:00|lokesh812@oksbi|nandini@icici|450.00|groceries",
            txn_id="TXN-TO3",
            timestamp=datetime(2026, 10, 9, 15, 0, 0),
            payer_vpa="lokesh812@oksbi",
            payee_vpa="nandini@icici",
            amount=Decimal("450.00"),
            remarks="groceries",
        )
        txn = self.service.process_pay(pay_cmd)

        job_cmd = TimeoutJobCommand(
            line_no=25,
            command_name="TIMEOUT_JOB",
            raw_line="TIMEOUT_JOB|2026-10-09 15:31:00",
            timestamp=datetime(2026, 10, 9, 15, 31, 0),  # 31 min
        )
        reversed_list = self.service.process_timeout_job(job_cmd)

        self.assertEqual(len(reversed_list), 1)
        self.assertEqual(txn.status, TransactionStatus.REVERSED)
        self.assertEqual(txn.reversed_at, datetime(2026, 10, 9, 15, 31, 0))

    def test_timeout_sweep_mixed_elapsed_times(self):
        """TIMEOUT_JOB with 3 transactions (35m, 30m, 20m) reverses exactly 2."""
        t1 = self.service.process_pay(PayCommand(
            line_no=26, command_name="PAY", raw_line="", txn_id="TXN-M1",
            timestamp=datetime(2026, 10, 1, 10, 0, 0),
            payer_vpa="aditya874@oksbi", payee_vpa="keerthi335@okaxis",
            amount=Decimal("100.00"), remarks="m1",
        ))
        t2 = self.service.process_pay(PayCommand(
            line_no=27, command_name="PAY", raw_line="", txn_id="TXN-M2",
            timestamp=datetime(2026, 10, 1, 10, 5, 0),
            payer_vpa="aditya874@oksbi", payee_vpa="keerthi335@okaxis",
            amount=Decimal("200.00"), remarks="m2",
        ))
        t3 = self.service.process_pay(PayCommand(
            line_no=28, command_name="PAY", raw_line="", txn_id="TXN-M3",
            timestamp=datetime(2026, 10, 1, 10, 15, 0),
            payer_vpa="aditya874@oksbi", payee_vpa="keerthi335@okaxis",
            amount=Decimal("300.00"), remarks="m3",
        ))

        # Sweep at 10:35:00:
        # t1: elapsed 35m -> reversed
        # t2: elapsed 30m -> reversed
        # t3: elapsed 20m -> still pending
        job_cmd = TimeoutJobCommand(29, "TIMEOUT_JOB", "", datetime(2026, 10, 1, 10, 35, 0))
        reversed_list = self.service.process_timeout_job(job_cmd)

        self.assertEqual(len(reversed_list), 2)
        self.assertEqual(t1.status, TransactionStatus.REVERSED)
        self.assertEqual(t2.status, TransactionStatus.REVERSED)
        self.assertEqual(t3.status, TransactionStatus.PENDING)

    def test_timeout_sweep_skips_settled_transactions(self):
        """TIMEOUT_JOB skips transactions that have already reached SUCCESS or FAILED."""
        t_success = self.service.process_pay(PayCommand(
            line_no=30, command_name="PAY", raw_line="", txn_id="TXN-S1",
            timestamp=datetime(2026, 10, 1, 10, 0, 0),
            payer_vpa="aditya874@oksbi", payee_vpa="keerthi335@okaxis",
            amount=Decimal("100.00"), remarks="s1",
        ))
        self.service.process_bene_response(
            BeneResponseCommand(31, "BENE_RESPONSE", "", "TXN-S1", datetime(2026, 10, 1, 10, 0, 5), BeneResponseType.ACK)
        )
        self.assertEqual(t_success.status, TransactionStatus.SUCCESS)

        job_cmd = TimeoutJobCommand(32, "TIMEOUT_JOB", "", datetime(2026, 10, 1, 10, 40, 0))
        reversed_list = self.service.process_timeout_job(job_cmd)

        self.assertEqual(len(reversed_list), 0)
        self.assertEqual(t_success.status, TransactionStatus.SUCCESS)

    def test_timeout_repeated_sweeps_idempotent(self):
        """Repeated sweeps do not refund transactions twice."""
        payer = self.master_data.users_by_vpa["aditya874@oksbi"]
        initial_balance = payer.balance

        self.service.process_pay(PayCommand(
            line_no=33, command_name="PAY", raw_line="", txn_id="TXN-REP",
            timestamp=datetime(2026, 10, 1, 10, 0, 0),
            payer_vpa="aditya874@oksbi", payee_vpa="keerthi335@okaxis",
            amount=Decimal("1000.00"), remarks="rep",
        ))

        # First sweep at 10:30:00
        rev1 = self.service.process_timeout_job(TimeoutJobCommand(34, "TIMEOUT_JOB", "", datetime(2026, 10, 1, 10, 30, 0)))
        self.assertEqual(len(rev1), 1)
        self.assertEqual(payer.balance, initial_balance)

        # Second sweep at 10:35:00
        rev2 = self.service.process_timeout_job(TimeoutJobCommand(35, "TIMEOUT_JOB", "", datetime(2026, 10, 1, 10, 35, 0)))
        self.assertEqual(len(rev2), 0)
        self.assertEqual(payer.balance, initial_balance)  # No double refund

    def test_timeout_empty_candidates(self):
        """TIMEOUT_JOB when no transactions are pending returns empty list."""
        rev = self.service.process_timeout_job(TimeoutJobCommand(36, "TIMEOUT_JOB", "", datetime(2026, 10, 1, 10, 0, 0)))
        self.assertEqual(len(rev), 0)

    def test_timeout_midnight_boundary_counters(self):
        """Transaction initiated at 23:45 on Day 1, timeout sweep at 00:15 on Day 2."""
        payer = self.master_data.users_by_vpa["aditya874@oksbi"]
        payer.counter_date = date(2026, 10, 1)

        t = self.service.process_pay(PayCommand(
            line_no=37, command_name="PAY", raw_line="", txn_id="TXN-MID",
            timestamp=datetime(2026, 10, 1, 23, 45, 0),
            payer_vpa="aditya874@oksbi", payee_vpa="keerthi335@okaxis",
            amount=Decimal("500.00"), remarks="mid",
        ))

        # Simulate timeout on Day 2
        job_cmd = TimeoutJobCommand(38, "TIMEOUT_JOB", "", datetime(2026, 10, 2, 0, 15, 0))
        rev = self.service.process_timeout_job(job_cmd)
        self.assertEqual(len(rev), 1)
        self.assertEqual(t.status, TransactionStatus.REVERSED)

    # -----------------------------------------------------------------------
    # 3. Late Acknowledgment Handling (BR-06: DEEMED_SUCCESS vs RECON_SHORTFALL)
    # -----------------------------------------------------------------------

    def test_late_ack_deemed_success_substantial_balance(self):
        """Official TXN-0015 scenario: Late ACK with sufficient balance transitions to DEEMED_SUCCESS."""
        payer = self.master_data.users_by_vpa["aditya874@oksbi"]
        payee = self.master_data.users_by_vpa["keerthi335@okaxis"]
        payee_initial_bal = payee.balance

        # 1. PAY TXN-0015
        t = self.service.process_pay(PayCommand(
            line_no=33, command_name="PAY", raw_line="", txn_id="TXN-0015",
            timestamp=datetime(2026, 10, 8, 10, 0, 0),
            payer_vpa="aditya874@oksbi", payee_vpa="keerthi335@okaxis",
            amount=Decimal("12000.00"), remarks="bill",
        ))
        # 2. NO_RESPONSE
        self.service.process_bene_response(BeneResponseCommand(
            line_no=34, command_name="BENE_RESPONSE", raw_line="", txn_id="TXN-0015",
            timestamp=datetime(2026, 10, 8, 10, 0, 2), response=BeneResponseType.NO_RESPONSE,
        ))
        # 3. TIMEOUT_JOB at 10:30:00
        rev = self.service.process_timeout_job(TimeoutJobCommand(
            line_no=35, command_name="TIMEOUT_JOB", raw_line="",
            timestamp=datetime(2026, 10, 8, 10, 30, 0),
        ))
        self.assertEqual(len(rev), 1)
        self.assertEqual(t.status, TransactionStatus.REVERSED)
        self.assertEqual(t.reversed_at, datetime(2026, 10, 8, 10, 30, 0))

        # 4. Late ACK at 10:30:40
        late_ack = BeneResponseCommand(
            line_no=36, command_name="BENE_RESPONSE", raw_line="", txn_id="TXN-0015",
            timestamp=datetime(2026, 10, 8, 10, 30, 40), response=BeneResponseType.ACK,
        )
        res = self.service.process_bene_response(late_ack)

        self.assertTrue(res.is_success)
        self.assertEqual(res.action_taken, "DEEMED_SUCCESS")
        self.assertEqual(t.status, TransactionStatus.DEEMED_SUCCESS)
        self.assertEqual(t.reversed_at, datetime(2026, 10, 8, 10, 30, 0))  # Preserved!
        self.assertEqual(t.deemed_success_at, datetime(2026, 10, 8, 10, 30, 40))  # Set!
        self.assertEqual(payee.balance, payee_initial_bal + Decimal("12000.00"))

    def test_late_ack_deemed_success_exact_equal_balance(self):
        """Late ACK when payer balance exactly equals txn.amount succeeds, reducing balance to 0.00."""
        payer = self.master_data.users_by_vpa["arjun598@paytm"]
        payer.balance = Decimal("1400.00")

        t = self.service.process_pay(PayCommand(
            line_no=40, command_name="PAY", raw_line="", txn_id="TXN-EXACT",
            timestamp=datetime(2026, 10, 10, 9, 0, 0),
            payer_vpa="arjun598@paytm", payee_vpa="geetha308@ybl",
            amount=Decimal("1400.00"), remarks="exact",
        ))
        # Timeout auto-reverses
        self.service.process_timeout_job(TimeoutJobCommand(41, "TIMEOUT_JOB", "", datetime(2026, 10, 10, 9, 30, 0)))
        self.assertEqual(payer.balance, Decimal("1400.00"))

        # Late ACK arrives
        res = self.service.process_bene_response(BeneResponseCommand(
            42, "BENE_RESPONSE", "", "TXN-EXACT", datetime(2026, 10, 10, 9, 30, 10), BeneResponseType.ACK
        ))
        self.assertTrue(res.is_success)
        self.assertEqual(t.status, TransactionStatus.DEEMED_SUCCESS)
        self.assertEqual(payer.balance, Decimal("0.00"))

    def test_late_ack_recon_shortfall_insufficient_balance(self):
        """Official TXN-0017 scenario: Late ACK when payer has spent funds logs RECON_SHORTFALL."""
        payer = self.master_data.users_by_vpa["arjun598@paytm"]
        payer.balance = Decimal("1500.00")

        # 1. PAY TXN-0017 for 1400.00
        t17 = self.service.process_pay(PayCommand(
            line_no=42, command_name="PAY", raw_line="", txn_id="TXN-0017",
            timestamp=datetime(2026, 10, 10, 9, 0, 0),
            payer_vpa="arjun598@paytm", payee_vpa="geetha308@ybl",
            amount=Decimal("1400.00"), remarks="bill",
        ))
        # 2. NO_RESPONSE
        self.service.process_bene_response(BeneResponseCommand(
            43, "BENE_RESPONSE", "", "TXN-0017", datetime(2026, 10, 10, 9, 0, 3), BeneResponseType.NO_RESPONSE
        ))
        # 3. TIMEOUT_JOB at 09:30:00 -> auto-reversed, balance restored to 1500.00
        self.service.process_timeout_job(TimeoutJobCommand(44, "TIMEOUT_JOB", "", datetime(2026, 10, 10, 9, 30, 0)))
        self.assertEqual(t17.status, TransactionStatus.REVERSED)
        self.assertEqual(payer.balance, Decimal("1500.00"))

        # 4. PAY TXN-0018 for 1200.00
        t18 = self.service.process_pay(PayCommand(
            line_no=45, command_name="PAY", raw_line="", txn_id="TXN-0018",
            timestamp=datetime(2026, 10, 10, 9, 45, 0),
            payer_vpa="arjun598@paytm", payee_vpa="hema891@ibl",
            amount=Decimal("1200.00"), remarks="bill",
        ))
        self.service.process_bene_response(BeneResponseCommand(
            46, "BENE_RESPONSE", "", "TXN-0018", datetime(2026, 10, 10, 9, 45, 3), BeneResponseType.ACK
        ))
        self.assertEqual(t18.status, TransactionStatus.SUCCESS)
        self.assertEqual(payer.balance, Decimal("300.00"))  # Only 300.00 remaining!

        # 5. Late ACK for TXN-0017 at 11:00:00
        late_ack_cmd = BeneResponseCommand(
            line_no=47, command_name="BENE_RESPONSE", raw_line="", txn_id="TXN-0017",
            timestamp=datetime(2026, 10, 10, 11, 0, 0), response=BeneResponseType.ACK,
        )
        res = self.service.process_bene_response(late_ack_cmd)

        self.assertTrue(res.is_success)
        self.assertEqual(res.action_taken, "RECON_SHORTFALL")
        self.assertEqual(t17.status, TransactionStatus.RECON_SHORTFALL)
        self.assertEqual(payer.balance, Decimal("300.00"))  # Anti-overdraft: NOT debited!
        self.assertIsNone(t17.deemed_success_at)
        self.assertEqual(t17.reversed_at, datetime(2026, 10, 10, 9, 30, 0))

        # Check reconciliation record
        records = [r for r in self.service.get_reconciliation_records() if r.txn_id == "TXN-0017"]
        self.assertEqual(len(records), 1)
        self.assertEqual(records[0].incident_type, "RECON_SHORTFALL")
        self.assertEqual(records[0].amount, Decimal("1400.00"))
        self.assertEqual(records[0].deficiency, Decimal("1100.00"))  # 1400 - 300

    def test_late_ack_recon_shortfall_zero_balance(self):
        """Late ACK when payer balance is 0.00 records full amount as shortfall."""
        payer = self.master_data.users_by_vpa["arjun598@paytm"]
        payer.balance = Decimal("500.00")

        t = self.service.process_pay(PayCommand(
            line_no=48, command_name="PAY", raw_line="", txn_id="TXN-Z1",
            timestamp=datetime(2026, 10, 10, 9, 0, 0),
            payer_vpa="arjun598@paytm", payee_vpa="geetha308@ybl",
            amount=Decimal("500.00"), remarks="zero",
        ))
        self.service.process_timeout_job(TimeoutJobCommand(49, "TIMEOUT_JOB", "", datetime(2026, 10, 10, 9, 30, 0)))
        payer.balance = Decimal("0.00")  # Balance spent elsewhere

        res = self.service.process_bene_response(BeneResponseCommand(
            50, "BENE_RESPONSE", "", "TXN-Z1", datetime(2026, 10, 10, 9, 35, 0), BeneResponseType.ACK
        ))
        self.assertEqual(t.status, TransactionStatus.RECON_SHORTFALL)
        self.assertEqual(res.reconciliation_record.deficiency, Decimal("500.00"))

    def test_duplicate_late_ack_on_deemed_success(self):
        """Duplicate late ACK on DEEMED_SUCCESS is an idempotent no-op."""
        payer = self.master_data.users_by_vpa["aditya874@oksbi"]
        t = self.service.process_pay(PayCommand(
            line_no=51, command_name="PAY", raw_line="", txn_id="TXN-DL1",
            timestamp=datetime(2026, 10, 8, 10, 0, 0),
            payer_vpa="aditya874@oksbi", payee_vpa="keerthi335@okaxis",
            amount=Decimal("1000.00"), remarks="dl",
        ))
        self.service.process_timeout_job(TimeoutJobCommand(52, "TIMEOUT_JOB", "", datetime(2026, 10, 8, 10, 30, 0)))
        ack_cmd = BeneResponseCommand(53, "BENE_RESPONSE", "", "TXN-DL1", datetime(2026, 10, 8, 10, 30, 10), BeneResponseType.ACK)
        self.service.process_bene_response(ack_cmd)
        bal_after_deemed = payer.balance

        # Duplicate late ACK
        dup_res = self.service.process_bene_response(ack_cmd)
        self.assertEqual(dup_res.action_taken, "DUPLICATE_IGNORED")
        self.assertEqual(t.status, TransactionStatus.DEEMED_SUCCESS)
        self.assertEqual(payer.balance, bal_after_deemed)

    def test_duplicate_late_ack_on_recon_shortfall(self):
        """Duplicate late ACK on RECON_SHORTFALL is an idempotent no-op."""
        payer = self.master_data.users_by_vpa["arjun598@paytm"]
        payer.balance = Decimal("100.00")
        t = self.service.process_pay(PayCommand(
            line_no=54, command_name="PAY", raw_line="", txn_id="TXN-DL2",
            timestamp=datetime(2026, 10, 10, 9, 0, 0),
            payer_vpa="arjun598@paytm", payee_vpa="geetha308@ybl",
            amount=Decimal("100.00"), remarks="dl2",
        ))
        self.service.process_timeout_job(TimeoutJobCommand(55, "TIMEOUT_JOB", "", datetime(2026, 10, 10, 9, 30, 0)))
        payer.balance = Decimal("0.00")  # Force shortfall
        ack_cmd = BeneResponseCommand(56, "BENE_RESPONSE", "", "TXN-DL2", datetime(2026, 10, 10, 9, 35, 0), BeneResponseType.ACK)
        self.service.process_bene_response(ack_cmd)
        self.assertEqual(t.status, TransactionStatus.RECON_SHORTFALL)

        # Duplicate late ACK
        dup_res = self.service.process_bene_response(ack_cmd)
        self.assertEqual(dup_res.action_taken, "DUPLICATE_IGNORED")
        self.assertEqual(t.status, TransactionStatus.RECON_SHORTFALL)

    def test_late_nack_after_reversal(self):
        """Late NACK on already reversed transaction remains REVERSED with no second refund."""
        payer = self.master_data.users_by_vpa["aditya874@oksbi"]
        initial_balance = payer.balance

        t = self.service.process_pay(PayCommand(
            line_no=57, command_name="PAY", raw_line="", txn_id="TXN-LN1",
            timestamp=datetime(2026, 10, 8, 10, 0, 0),
            payer_vpa="aditya874@oksbi", payee_vpa="keerthi335@okaxis",
            amount=Decimal("2000.00"), remarks="ln",
        ))
        self.service.process_timeout_job(TimeoutJobCommand(58, "TIMEOUT_JOB", "", datetime(2026, 10, 8, 10, 30, 0)))
        self.assertEqual(payer.balance, initial_balance)

        # Late NACK arrives
        res = self.service.process_bene_response(BeneResponseCommand(
            59, "BENE_RESPONSE", "", "TXN-LN1", datetime(2026, 10, 8, 10, 35, 0), BeneResponseType.NACK
        ))
        self.assertTrue(res.is_success)
        self.assertEqual(res.action_taken, "DUPLICATE_IGNORED")
        self.assertEqual(t.status, TransactionStatus.REVERSED)
        self.assertEqual(payer.balance, initial_balance)  # No second refund!

    # -----------------------------------------------------------------------
    # 4. Financial Invariant & Accounting Integrity
    # -----------------------------------------------------------------------

    def test_closed_system_balance_conserved_across_all_phase4_scenarios(self):
        """Closed-system balance (users + merchants + switch clearing) is 100% conserved."""
        all_users = list(self.master_data.users_by_vpa.values())
        all_merchants = list(self.master_data.merchants_by_vpa.values())
        initial_total = self.ledger.total_system_balance(all_users, all_merchants)

        # Scenario 1: Initiation + ACK (P2P)
        self.service.process_pay(PayCommand(
            1, "PAY", "", "TXN-INV1", datetime(2026, 10, 1, 9, 0, 0),
            "aditya874@oksbi", "keerthi335@okaxis", Decimal("1000.00"), "inv1"
        ))
        self.assertEqual(self.ledger.total_system_balance(all_users, all_merchants), initial_total)
        self.service.process_bene_response(BeneResponseCommand(
            2, "BENE_RESPONSE", "", "TXN-INV1", datetime(2026, 10, 1, 9, 0, 5), BeneResponseType.ACK
        ))
        self.assertEqual(self.ledger.total_system_balance(all_users, all_merchants), initial_total)

        # Scenario 2: Initiation + NACK
        self.service.process_pay(PayCommand(
            3, "PAY", "", "TXN-INV2", datetime(2026, 10, 1, 9, 5, 0),
            "aditya874@oksbi", "keerthi335@okaxis", Decimal("500.00"), "inv2"
        ))
        self.assertEqual(self.ledger.total_system_balance(all_users, all_merchants), initial_total)
        self.service.process_bene_response(BeneResponseCommand(
            4, "BENE_RESPONSE", "", "TXN-INV2", datetime(2026, 10, 1, 9, 5, 5), BeneResponseType.NACK
        ))
        self.assertEqual(self.ledger.total_system_balance(all_users, all_merchants), initial_total)

        # Scenario 3: Initiation + Timeout Auto-Reversal + Late ACK DEEMED_SUCCESS
        self.service.process_pay(PayCommand(
            5, "PAY", "", "TXN-INV3", datetime(2026, 10, 1, 10, 0, 0),
            "aditya874@oksbi", "keerthi335@okaxis", Decimal("2000.00"), "inv3"
        ))
        self.assertEqual(self.ledger.total_system_balance(all_users, all_merchants), initial_total)
        self.service.process_timeout_job(TimeoutJobCommand(6, "TIMEOUT_JOB", "", datetime(2026, 10, 1, 10, 30, 0)))
        self.assertEqual(self.ledger.total_system_balance(all_users, all_merchants), initial_total)
        self.service.process_bene_response(BeneResponseCommand(
            7, "BENE_RESPONSE", "", "TXN-INV3", datetime(2026, 10, 1, 10, 30, 40), BeneResponseType.ACK
        ))
        self.assertEqual(self.ledger.total_system_balance(all_users, all_merchants), initial_total)

    def test_ledger_double_entry_balance(self):
        """Sum of all debits must equal sum of all credits across all ledger entries."""
        self.service.process_pay(PayCommand(
            1, "PAY", "", "TXN-BAL1", datetime(2026, 10, 1, 9, 0, 0),
            "aditya874@oksbi", "keerthi335@okaxis", Decimal("3000.00"), "b1"
        ))
        self.service.process_bene_response(BeneResponseCommand(
            2, "BENE_RESPONSE", "", "TXN-BAL1", datetime(2026, 10, 1, 9, 0, 5), BeneResponseType.ACK
        ))

        all_entries = self.ledger.get_all_entries()
        total_debits = sum((e.debit for e in all_entries if e.debit is not None), Decimal("0.00"))
        total_credits = sum((e.credit for e in all_entries if e.credit is not None), Decimal("0.00"))
        self.assertEqual(total_debits, total_credits)


if __name__ == "__main__":
    unittest.main()
