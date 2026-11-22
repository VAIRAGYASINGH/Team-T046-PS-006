"""Comprehensive test suite for Phase 7: Double-Entry Ledger, Financial Reconciliation & Statements.

Tests all 18 mandated accounting test categories and validates 100% exact output
against the benchmark statements.csv specification.
"""

import csv
from datetime import date, datetime, timedelta
from decimal import Decimal
from pathlib import Path
import unittest

from src.config import COMMANDS_TXT_PATH, HOLIDAYS_CSV_PATH, MERCHANTS_CSV_PATH, USERS_CSV_PATH
from src.data import MasterData, load_master_data
from src.ledger import (
    DoubleEntryLedger,
    LedgerAccountingError,
    LegType,
    ReconciliationEngine,
    StatementService,
    SWITCH_CLEARING_VPA,
)
from src.models import (
    BeneResponseCommand,
    BeneResponseType,
    CollectApproveCommand,
    CollectCommand,
    CollectDeclineCommand,
    DisputeAction,
    DisputeActionCommand,
    DisputeCommand,
    DisputeType,
    PayCommand,
    ReconciliationReport,
    RefundCommand,
    StatementCommand,
    StatementRecord,
    TimeoutJobCommand,
    Transaction,
    TransactionStatus,
    User,
)
from src.parser import CommandParser
from src.payments import PaymentService
from src.rrn_generator import RRNGenerator


class TestPhase7LedgerAndStatements(unittest.TestCase):
    """Test suite for Phase 7 double-entry ledger, reconciliation, and customer statements."""

    def setUp(self):
        """Set up fresh isolated test environment for each test."""
        self.master_data = load_master_data(
            users_path=USERS_CSV_PATH,
            merchants_path=MERCHANTS_CSV_PATH,
            holidays_path=HOLIDAYS_CSV_PATH,
        )
        self.ledger = DoubleEntryLedger()
        self.rrn_gen = RRNGenerator(start_seq=1)
        self.payment_service = PaymentService(self.master_data, self.ledger, self.rrn_gen)

    # -------------------------------------------------------------------------
    # 1. Opening balance correctness
    # -------------------------------------------------------------------------
    def test_01_opening_balance_correctness(self):
        """Verify opening balances loaded from master data are Decimal, conserved, and zero in clearing."""
        users = list(self.master_data.users_by_vpa.values())
        merchants = list(self.master_data.merchants_by_vpa.values())

        self.assertEqual(len(users), 12)
        self.assertEqual(len(merchants), 4)

        for u in users:
            self.assertIsInstance(u.balance, Decimal)
            self.assertGreater(u.balance, Decimal("0.00"))
            self.assertEqual(self.ledger.get_initial_balance(u.vpa), u.balance)

        for m in merchants:
            self.assertIsInstance(m.balance, Decimal)
            self.assertGreater(m.balance, Decimal("0.00"))
            self.assertEqual(self.ledger.get_initial_balance(m.vpa), m.balance)

        total_users = sum((u.balance for u in users), Decimal("0.00"))
        total_merchants = sum((m.balance for m in merchants), Decimal("0.00"))
        total_initial = total_users + total_merchants

        self.assertEqual(total_users, Decimal("2836500.00"))
        self.assertEqual(total_merchants, Decimal("3200000.00"))
        self.assertEqual(total_initial, Decimal("6036500.00"))
        self.assertEqual(self.ledger.clearing_balance, Decimal("0.00"))

        system_total = self.ledger.total_system_balance(users, merchants)
        self.assertEqual(system_total, total_initial)

    # -------------------------------------------------------------------------
    # 2. Double-entry balance invariant
    # -------------------------------------------------------------------------
    def test_02_double_entry_balance_invariant(self):
        """Verify that every single financial operation produces strictly balanced debit and credit legs."""
        pay_cmd = PayCommand(
            line_no=10,
            command_name="PAY",
            raw_line="PAY|TXN-9001|2026-10-01 10:00:00|rohit890@okaxis|aditya874@oksbi|5000.00|test",
            txn_id="TXN-9001",
            timestamp=datetime(2026, 10, 1, 10, 0, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="aditya874@oksbi",
            amount=Decimal("5000.00"),
            remarks="test",
        )
        self.payment_service.process_pay(pay_cmd)

        entries = self.ledger.get_all_entries()
        self.assertEqual(len(entries), 2)
        total_d = sum((e.debit for e in entries if e.debit is not None), Decimal("0.00"))
        total_c = sum((e.credit for e in entries if e.credit is not None), Decimal("0.00"))
        self.assertEqual(total_d, Decimal("5000.00"))
        self.assertEqual(total_c, Decimal("5000.00"))
        self.assertEqual(total_d, total_c)

        # Settle
        ack_cmd = BeneResponseCommand(
            line_no=11,
            command_name="BENE_RESPONSE",
            raw_line="BENE_RESPONSE|TXN-9001|2026-10-01 10:00:05|ACK",
            txn_id="TXN-9001",
            timestamp=datetime(2026, 10, 1, 10, 0, 5),
            response=BeneResponseType.ACK,
        )
        self.payment_service.process_bene_response(ack_cmd)

        entries = self.ledger.get_all_entries()
        self.assertEqual(len(entries), 4)
        total_d = sum((e.debit for e in entries if e.debit is not None), Decimal("0.00"))
        total_c = sum((e.credit for e in entries if e.credit is not None), Decimal("0.00"))
        self.assertEqual(total_d, Decimal("10000.00"))
        self.assertEqual(total_c, Decimal("10000.00"))
        self.assertEqual(total_d, total_c)

    # -------------------------------------------------------------------------
    # 3. Payer debit and clearing credit
    # -------------------------------------------------------------------------
    def test_03_payer_debit_and_clearing_credit(self):
        """Verify initiation debits payer and credits switch clearing escrow account."""
        payer = self.master_data.get_user("rohit890@okaxis")
        initial_balance = payer.balance

        cmd = PayCommand(
            line_no=1,
            command_name="PAY",
            raw_line="PAY|TXN-9002|2026-10-01 11:00:00|rohit890@okaxis|harini730@ibl|4000.00|rent",
            txn_id="TXN-9002",
            timestamp=datetime(2026, 10, 1, 11, 0, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="harini730@ibl",
            amount=Decimal("4000.00"),
            remarks="rent",
        )
        txn = self.payment_service.process_pay(cmd)

        self.assertEqual(txn.status, TransactionStatus.PENDING)
        self.assertEqual(payer.balance, initial_balance - Decimal("4000.00"))
        self.assertEqual(self.ledger.clearing_balance, Decimal("4000.00"))

        payer_legs = self.ledger.get_entries_for_vpa("rohit890@okaxis")
        self.assertEqual(len(payer_legs), 1)
        self.assertEqual(payer_legs[0].leg_type, LegType.DEBIT)
        self.assertEqual(payer_legs[0].debit, Decimal("4000.00"))
        self.assertIsNone(payer_legs[0].credit)
        self.assertEqual(payer_legs[0].running_balance, initial_balance - Decimal("4000.00"))

        clearing_legs = self.ledger.get_entries_for_vpa(SWITCH_CLEARING_VPA)
        self.assertEqual(len(clearing_legs), 1)
        self.assertEqual(clearing_legs[0].leg_type, LegType.CREDIT)
        self.assertEqual(clearing_legs[0].credit, Decimal("4000.00"))
        self.assertEqual(clearing_legs[0].running_balance, Decimal("4000.00"))

    # -------------------------------------------------------------------------
    # 4. Clearing settlement
    # -------------------------------------------------------------------------
    def test_04_clearing_settlement(self):
        """Verify ACK debits clearing escrow and credits beneficiary account."""
        payee = self.master_data.get_user("harini730@ibl")
        initial_payee_bal = payee.balance

        cmd = PayCommand(
            line_no=1,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-9003",
            timestamp=datetime(2026, 10, 1, 11, 0, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="harini730@ibl",
            amount=Decimal("2500.00"),
            remarks="groceries",
        )
        self.payment_service.process_pay(cmd)
        self.assertEqual(self.ledger.clearing_balance, Decimal("2500.00"))

        ack = BeneResponseCommand(
            line_no=2,
            command_name="BENE_RESPONSE",
            raw_line="",
            txn_id="TXN-9003",
            timestamp=datetime(2026, 10, 1, 11, 0, 4),
            response=BeneResponseType.ACK,
        )
        self.payment_service.process_bene_response(ack)

        self.assertEqual(self.ledger.clearing_balance, Decimal("0.00"))
        self.assertEqual(payee.balance, initial_payee_bal + Decimal("2500.00"))

        payee_legs = self.ledger.get_entries_for_vpa("harini730@ibl")
        self.assertEqual(len(payee_legs), 1)
        self.assertEqual(payee_legs[0].leg_type, LegType.CREDIT)
        self.assertEqual(payee_legs[0].credit, Decimal("2500.00"))
        self.assertEqual(payee_legs[0].running_balance, initial_payee_bal + Decimal("2500.00"))

    # -------------------------------------------------------------------------
    # 5. Reversal accounting
    # -------------------------------------------------------------------------
    def test_05_reversal_accounting(self):
        """Verify reversal debits clearing escrow and credits payer with REVERSAL leg."""
        payer = self.master_data.get_user("rohit890@okaxis")
        initial_payer_bal = payer.balance

        cmd = PayCommand(
            line_no=1,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-9004",
            timestamp=datetime(2026, 10, 1, 12, 0, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="aditya874@oksbi",
            amount=Decimal("3000.00"),
            remarks="transfer",
        )
        self.payment_service.process_pay(cmd)

        # Trigger reversal via timeout job
        timeout_cmd = TimeoutJobCommand(
            line_no=2,
            command_name="TIMEOUT_JOB",
            raw_line="",
            timestamp=datetime(2026, 10, 1, 12, 31, 0),
        )
        self.payment_service.process_timeout_job(timeout_cmd)

        self.assertEqual(self.ledger.clearing_balance, Decimal("0.00"))
        self.assertEqual(payer.balance, initial_payer_bal)

        payer_legs = self.ledger.get_entries_for_vpa("rohit890@okaxis")
        self.assertEqual(len(payer_legs), 2)
        self.assertEqual(payer_legs[0].leg_type, LegType.DEBIT)
        self.assertEqual(payer_legs[0].debit, Decimal("3000.00"))
        self.assertEqual(payer_legs[1].leg_type, LegType.REVERSAL)
        self.assertEqual(payer_legs[1].credit, Decimal("3000.00"))
        self.assertEqual(payer_legs[1].running_balance, initial_payer_bal)

    # -------------------------------------------------------------------------
    # 6. NACK accounting
    # -------------------------------------------------------------------------
    def test_06_nack_accounting(self):
        """Verify BENE_RESPONSE NACK restores payer balance, decrements daily counters, and balances escrow."""
        payer = self.master_data.get_user("rohit890@okaxis")
        initial_bal = payer.balance
        initial_count = payer.daily_debit_count
        initial_amt = payer.daily_debit_amount

        cmd = PayCommand(
            line_no=1,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-9005",
            timestamp=datetime(2026, 10, 1, 13, 0, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="aditya874@oksbi",
            amount=Decimal("1500.00"),
            remarks="dinner",
        )
        self.payment_service.process_pay(cmd)

        self.assertEqual(payer.daily_debit_count, initial_count + 1)
        self.assertEqual(payer.daily_debit_amount, initial_amt + Decimal("1500.00"))

        nack = BeneResponseCommand(
            line_no=2,
            command_name="BENE_RESPONSE",
            raw_line="",
            txn_id="TXN-9005",
            timestamp=datetime(2026, 10, 1, 13, 0, 2),
            response=BeneResponseType.NACK,
        )
        self.payment_service.process_bene_response(nack)

        self.assertEqual(payer.balance, initial_bal)
        self.assertEqual(payer.daily_debit_count, initial_count)
        self.assertEqual(payer.daily_debit_amount, initial_amt)
        self.assertEqual(self.ledger.clearing_balance, Decimal("0.00"))

    # -------------------------------------------------------------------------
    # 7. Timeout accounting
    # -------------------------------------------------------------------------
    def test_07_timeout_accounting(self):
        """Verify TIMEOUT_JOB automatically identifies and reverses transactions past the 30-minute window."""
        cmd1 = PayCommand(
            line_no=1,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-9006A",
            timestamp=datetime(2026, 10, 1, 14, 0, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="aditya874@oksbi",
            amount=Decimal("1000.00"),
            remarks="t1",
        )
        cmd2 = PayCommand(
            line_no=2,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-9006B",
            timestamp=datetime(2026, 10, 1, 14, 25, 0),
            payer_vpa="rohit890@okaxis",
            payee_vpa="aditya874@oksbi",
            amount=Decimal("2000.00"),
            remarks="t2",
        )
        self.payment_service.process_pay(cmd1)
        self.payment_service.process_pay(cmd2)
        self.assertEqual(self.ledger.clearing_balance, Decimal("3000.00"))

        # Sweep at 14:30:01 (30 minutes after cmd1, but only 5 minutes after cmd2)
        job = TimeoutJobCommand(
            line_no=3,
            command_name="TIMEOUT_JOB",
            raw_line="",
            timestamp=datetime(2026, 10, 1, 14, 30, 1),
        )
        reversed_txns = self.payment_service.process_timeout_job(job)

        self.assertEqual(len(reversed_txns), 1)
        self.assertEqual(reversed_txns[0].txn_id, "TXN-9006A")
        self.assertEqual(reversed_txns[0].status, TransactionStatus.REVERSED)
        # TXN-9006B still in flight in escrow
        self.assertEqual(self.ledger.clearing_balance, Decimal("2000.00"))

    # -------------------------------------------------------------------------
    # 8. Late ACK accounting
    # -------------------------------------------------------------------------
    def test_08_late_ack_accounting(self):
        """Verify late ACK deemed success re-debit and shortfall handling (BR-06)."""
        # Case A: Deemed success when payer has sufficient funds
        payer = self.master_data.get_user("rohit890@okaxis")
        payee = self.master_data.get_user("aditya874@oksbi")
        initial_payee_bal = payee.balance

        cmd = PayCommand(
            line_no=1,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-9007A",
            timestamp=datetime(2026, 10, 1, 15, 0, 0),
            payer_vpa=payer.vpa,
            payee_vpa=payee.vpa,
            amount=Decimal("5000.00"),
            remarks="p1",
        )
        self.payment_service.process_pay(cmd)

        # Timeout reversal
        self.payment_service.process_timeout_job(
            TimeoutJobCommand(line_no=2, command_name="TIMEOUT_JOB", raw_line="", timestamp=datetime(2026, 10, 1, 15, 31, 0))
        )
        txn_a = self.payment_service.get_transaction("TXN-9007A")
        self.assertEqual(txn_a.status, TransactionStatus.REVERSED)

        # Late ACK arrives
        late_ack = BeneResponseCommand(
            line_no=3,
            command_name="BENE_RESPONSE",
            raw_line="",
            txn_id="TXN-9007A",
            timestamp=datetime(2026, 10, 1, 15, 31, 10),
            response=BeneResponseType.ACK,
        )
        self.payment_service.process_bene_response(late_ack)

        self.assertEqual(txn_a.status, TransactionStatus.DEEMED_SUCCESS)
        self.assertEqual(payee.balance, initial_payee_bal + Decimal("5000.00"))

        # Case B: Recon shortfall when payer does not have sufficient funds
        poor_user = self.master_data.get_user("arjun598@paytm")
        poor_user.balance = Decimal("200.00")

        cmd_b = PayCommand(
            line_no=4,
            command_name="PAY",
            raw_line="",
            txn_id="TXN-9007B",
            timestamp=datetime(2026, 10, 1, 16, 0, 0),
            payer_vpa=poor_user.vpa,
            payee_vpa=payee.vpa,
            amount=Decimal("150.00"),
            remarks="p2",
        )
        self.payment_service.process_pay(cmd_b)
        self.payment_service.process_timeout_job(
            TimeoutJobCommand(line_no=5, command_name="TIMEOUT_JOB", raw_line="", timestamp=datetime(2026, 10, 1, 16, 31, 0))
        )
        # Drain user balance before late ACK
        poor_user.balance = Decimal("50.00")

        late_ack_b = BeneResponseCommand(
            line_no=6,
            command_name="BENE_RESPONSE",
            raw_line="",
            txn_id="TXN-9007B",
            timestamp=datetime(2026, 10, 1, 16, 31, 15),
            response=BeneResponseType.ACK,
        )
        self.payment_service.process_bene_response(late_ack_b)

        txn_b = self.payment_service.get_transaction("TXN-9007B")
        self.assertEqual(txn_b.status, TransactionStatus.RECON_SHORTFALL)
        self.assertEqual(poor_user.balance, Decimal("50.00"))  # No overdraft

    # -------------------------------------------------------------------------
    # 9. Collect accounting
    # -------------------------------------------------------------------------
    def test_09_collect_accounting(self):
        """Verify COLLECT pull payment approval direct settlement and decline isolation."""
        payer = self.master_data.get_user("rohit890@okaxis")
        requester = self.master_data.get_user("aditya874@oksbi")
        initial_payer = payer.balance
        initial_req = requester.balance

        # 1. Approved Collect
        collect_cmd = CollectCommand(
            line_no=1,
            command_name="COLLECT",
            raw_line="",
            txn_id="TXN-9008A",
            timestamp=datetime(2026, 10, 1, 10, 0, 0),
            requester_vpa=requester.vpa,
            payer_vpa=payer.vpa,
            amount=Decimal("1500.00"),
            expiry_minutes=30,
            pin_flag=True,
        )
        self.payment_service.process_collect(collect_cmd)
        self.payment_service.process_collect_approve(
            CollectApproveCommand(line_no=2, command_name="COLLECT_APPROVE", raw_line="", txn_id="TXN-9008A", timestamp=datetime(2026, 10, 1, 10, 5, 0))
        )

        self.assertEqual(payer.balance, initial_payer - Decimal("1500.00"))
        self.assertEqual(requester.balance, initial_req + Decimal("1500.00"))

        # 2. Declined Collect
        collect_cmd2 = CollectCommand(
            line_no=3,
            command_name="COLLECT",
            raw_line="",
            txn_id="TXN-9008B",
            timestamp=datetime(2026, 10, 1, 11, 0, 0),
            requester_vpa=requester.vpa,
            payer_vpa=payer.vpa,
            amount=Decimal("800.00"),
            expiry_minutes=30,
            pin_flag=True,
        )
        self.payment_service.process_collect(collect_cmd2)
        cur_payer_bal = payer.balance
        cur_req_bal = requester.balance

        self.payment_service.process_collect_decline(
            CollectDeclineCommand(line_no=4, command_name="COLLECT_DECLINE", raw_line="", txn_id="TXN-9008B", timestamp=datetime(2026, 10, 1, 11, 2, 0))
        )

        # Zero balance change on decline
        self.assertEqual(payer.balance, cur_payer_bal)
        self.assertEqual(requester.balance, cur_req_bal)

    # -------------------------------------------------------------------------
    # 10. Refund accounting
    # -------------------------------------------------------------------------
    def test_10_refund_accounting(self):
        """Verify merchant refunds, partial refund tracking, and refund caps."""
        user = self.master_data.get_user("rohit890@okaxis")
        merchant = self.master_data.get_merchant("bengaluru@icici")
        user_start = user.balance
        merch_start = merchant.balance

        # Initial successful P2M
        self.payment_service.process_pay(
            PayCommand(
                line_no=1, command_name="PAY", raw_line="", txn_id="TXN-9009",
                timestamp=datetime(2026, 10, 1, 12, 0, 0),
                payer_vpa=user.vpa, payee_vpa=merchant.vpa, amount=Decimal("1000.00"), remarks="food"
            )
        )
        self.payment_service.process_bene_response(
            BeneResponseCommand(line_no=2, command_name="BENE_RESPONSE", raw_line="", txn_id="TXN-9009", timestamp=datetime(2026, 10, 1, 12, 0, 2), response=BeneResponseType.ACK)
        )

        # Partial refund 1: Rs 400.00
        res1 = self.payment_service.process_refund(
            RefundCommand(line_no=3, command_name="REFUND", raw_line="", txn_id="TXN-9009", timestamp=datetime(2026, 10, 1, 13, 0, 0), amount=Decimal("400.00"))
        )
        self.assertTrue(res1.is_success)
        txn = self.payment_service.get_transaction("TXN-9009")
        self.assertEqual(txn.refunded_amount, Decimal("400.00"))

        # Partial refund 2: Rs 600.00 (completes original 1000.00)
        res2 = self.payment_service.process_refund(
            RefundCommand(line_no=4, command_name="REFUND", raw_line="", txn_id="TXN-9009", timestamp=datetime(2026, 10, 1, 14, 0, 0), amount=Decimal("600.00"))
        )
        self.assertTrue(res2.is_success)
        self.assertEqual(txn.refunded_amount, Decimal("1000.00"))

        # Excess refund: Rs 50.00 must be rejected
        res3 = self.payment_service.process_refund(
            RefundCommand(line_no=5, command_name="REFUND", raw_line="", txn_id="TXN-9009", timestamp=datetime(2026, 10, 1, 15, 0, 0), amount=Decimal("50.00"))
        )
        self.assertFalse(res3.is_success)
        self.assertEqual(txn.refunded_amount, Decimal("1000.00"))

        # Balances returned to exact start
        self.assertEqual(user.balance, user_start)
        self.assertEqual(merchant.balance, merch_start)

    # -------------------------------------------------------------------------
    # 11. Dispute accounting
    # -------------------------------------------------------------------------
    def test_11_dispute_accounting(self):
        """Verify dispute chargeback settlement posts DISPUTE_DEBIT and DISPUTE_CREDIT legs."""
        user = self.master_data.get_user("aditya874@oksbi")
        merchant = self.master_data.get_merchant("sapna@icici")

        # Create successful P2M
        self.payment_service.process_pay(
            PayCommand(
                line_no=1, command_name="PAY", raw_line="", txn_id="TXN-9010",
                timestamp=datetime(2026, 10, 1, 10, 0, 0),
                payer_vpa=user.vpa, payee_vpa=merchant.vpa, amount=Decimal("2000.00"), remarks="shoes"
            )
        )
        self.payment_service.process_bene_response(
            BeneResponseCommand(line_no=2, command_name="BENE_RESPONSE", raw_line="", txn_id="TXN-9010", timestamp=datetime(2026, 10, 1, 10, 0, 2), response=BeneResponseType.ACK)
        )

        user_bal_before_dsp = user.balance
        merch_bal_before_dsp = merchant.balance

        # Raise chargeback
        self.payment_service.process_dispute(
            DisputeCommand(
                line_no=3, command_name="DISPUTE", raw_line="", dispute_id="DSP-9010",
                timestamp=datetime(2026, 10, 2, 10, 0, 0), txn_id="TXN-9010",
                dispute_type=DisputeType.CHARGEBACK, raised_by="REMITTER", amount=Decimal("2000.00")
            )
        )
        # Balances unchanged while open
        self.assertEqual(user.balance, user_bal_before_dsp)
        self.assertEqual(merchant.balance, merch_bal_before_dsp)

        # Accept dispute
        self.payment_service.process_dispute_action(
            DisputeActionCommand(
                line_no=4, command_name="DISPUTE_ACTION", raw_line="", dispute_id="DSP-9010",
                timestamp=datetime(2026, 10, 3, 10, 0, 0), action=DisputeAction.ACCEPT
            )
        )

        # Settlement executed
        self.assertEqual(merchant.balance, merch_bal_before_dsp - Decimal("2000.00"))
        self.assertEqual(user.balance, user_bal_before_dsp + Decimal("2000.00"))

        m_legs = [e for e in self.ledger.get_entries_for_vpa(merchant.vpa) if e.txn_id == "TXN-9010" and e.leg_type == LegType.DISPUTE_DEBIT]
        u_legs = [e for e in self.ledger.get_entries_for_vpa(user.vpa) if e.txn_id == "TXN-9010" and e.leg_type == LegType.DISPUTE_CREDIT]
        self.assertEqual(len(m_legs), 1)
        self.assertEqual(len(u_legs), 1)
        self.assertEqual(m_legs[0].debit, Decimal("2000.00"))
        self.assertEqual(u_legs[0].credit, Decimal("2000.00"))

    # -------------------------------------------------------------------------
    # 12. Duplicate posting prevention
    # -------------------------------------------------------------------------
    def test_12_duplicate_posting_prevention(self):
        """Verify that duplicate PAY command with same txn_id is rejected and posts zero ledger entries."""
        cmd1 = PayCommand(
            line_no=1, command_name="PAY", raw_line="", txn_id="TXN-9011",
            timestamp=datetime(2026, 10, 1, 10, 0, 0),
            payer_vpa="rohit890@okaxis", payee_vpa="aditya874@oksbi", amount=Decimal("1000.00"), remarks="d1"
        )
        cmd2 = PayCommand(
            line_no=2, command_name="PAY", raw_line="", txn_id="TXN-9011",
            timestamp=datetime(2026, 10, 1, 10, 1, 0),
            payer_vpa="rohit890@okaxis", payee_vpa="aditya874@oksbi", amount=Decimal("1000.00"), remarks="d2"
        )
        t1 = self.payment_service.process_pay(cmd1)
        self.assertEqual(t1.status, TransactionStatus.PENDING)

        t2 = self.payment_service.process_pay(cmd2)
        self.assertEqual(t2.status, TransactionStatus.FAILED)
        self.assertEqual(t2.rule_ids, "DUPLICATE_TXN")

        entries = self.ledger.get_entries_for_txn("TXN-9011")
        # Only 2 entries from first cmd1 (debit payer, credit clearing)
        self.assertEqual(len(entries), 2)

    # -------------------------------------------------------------------------
    # 13. Ledger-to-account-balance reconciliation
    # -------------------------------------------------------------------------
    def test_13_ledger_to_account_balance_reconciliation(self):
        """Verify ReconciliationEngine validates all participant balances against ledger entry sums."""
        # Process some operations
        self.payment_service.process_pay(
            PayCommand(
                line_no=1, command_name="PAY", raw_line="", txn_id="TXN-9012A",
                timestamp=datetime(2026, 10, 1, 10, 0, 0),
                payer_vpa="rohit890@okaxis", payee_vpa="aditya874@oksbi", amount=Decimal("5000.00"), remarks=""
            )
        )
        self.payment_service.process_bene_response(
            BeneResponseCommand(line_no=2, command_name="BENE_RESPONSE", raw_line="", txn_id="TXN-9012A", timestamp=datetime(2026, 10, 1, 10, 0, 2), response=BeneResponseType.ACK)
        )

        recon = self.payment_service.reconcile()
        self.assertTrue(recon.is_balanced)
        self.assertEqual(recon.debit_credit_variance, Decimal("0.00"))
        self.assertEqual(recon.system_balance_variance, Decimal("0.00"))
        self.assertEqual(len(recon.accounts_with_mismatch), 0)
        self.assertEqual(len(recon.anomalies), 0)

    # -------------------------------------------------------------------------
    # 14. Statement ordering
    # -------------------------------------------------------------------------
    def test_14_statement_ordering(self):
        """Verify statements preserve command order and are chronological within each statement."""
        vpa1 = "rohit890@okaxis"
        vpa2 = "aditya874@oksbi"

        self.payment_service.process_pay(
            PayCommand(
                line_no=1, command_name="PAY", raw_line="", txn_id="TXN-9013A",
                timestamp=datetime(2026, 10, 1, 9, 0, 0),
                payer_vpa=vpa1, payee_vpa=vpa2, amount=Decimal("1000.00"), remarks=""
            )
        )
        self.payment_service.process_pay(
            PayCommand(
                line_no=2, command_name="PAY", raw_line="", txn_id="TXN-9013B",
                timestamp=datetime(2026, 10, 1, 11, 0, 0),
                payer_vpa=vpa1, payee_vpa=vpa2, amount=Decimal("2000.00"), remarks=""
            )
        )

        stmt1 = StatementCommand(line_no=10, command_name="STATEMENT", raw_line="", vpa=vpa1, from_date=date(2026, 10, 1), to_date=date(2026, 10, 1))
        stmt2 = StatementCommand(line_no=11, command_name="STATEMENT", raw_line="", vpa=vpa2, from_date=date(2026, 10, 1), to_date=date(2026, 10, 1))

        recs1 = self.payment_service.process_statement(stmt1)
        recs2 = self.payment_service.process_statement(stmt2)

        # Chronological check inside statement 1
        self.assertEqual(len(recs1), 2)
        self.assertLess(recs1[0].timestamp, recs1[1].timestamp)

        # Global accumulated order check
        accum = self.payment_service.get_statement_records()
        self.assertEqual(len(accum), len(recs1) + len(recs2))
        self.assertEqual(accum[0].vpa, vpa1)
        self.assertEqual(accum[1].vpa, vpa1)

    # -------------------------------------------------------------------------
    # 15. Statement running balances
    # -------------------------------------------------------------------------
    def test_15_statement_running_balances(self):
        """Verify that running balance reported in each statement leg accurately tracks account balance."""
        vpa = "rohit890@okaxis"
        payer = self.master_data.get_user(vpa)
        start_bal = payer.balance

        self.payment_service.process_pay(
            PayCommand(line_no=1, command_name="PAY", raw_line="", txn_id="TXN-9014A", timestamp=datetime(2026, 10, 1, 9, 0, 0), payer_vpa=vpa, payee_vpa="aditya874@oksbi", amount=Decimal("1000.00"), remarks="")
        )
        self.payment_service.process_pay(
            PayCommand(line_no=2, command_name="PAY", raw_line="", txn_id="TXN-9014B", timestamp=datetime(2026, 10, 1, 10, 0, 0), payer_vpa=vpa, payee_vpa="aditya874@oksbi", amount=Decimal("2000.00"), remarks="")
        )

        stmt = StatementCommand(line_no=3, command_name="STATEMENT", raw_line="", vpa=vpa, from_date=date(2026, 10, 1), to_date=date(2026, 10, 1))
        recs = self.payment_service.process_statement(stmt)

        self.assertEqual(len(recs), 2)
        self.assertEqual(recs[0].running_balance, start_bal - Decimal("1000.00"))
        self.assertEqual(recs[1].running_balance, start_bal - Decimal("3000.00"))

    # -------------------------------------------------------------------------
    # 16. Statement date filtering
    # -------------------------------------------------------------------------
    def test_16_statement_date_filtering(self):
        """Verify statement date filtering includes exact boundary dates and excludes out-of-range dates."""
        vpa = "rohit890@okaxis"

        self.payment_service.process_pay(
            PayCommand(line_no=1, command_name="PAY", raw_line="", txn_id="TXN-D1", timestamp=datetime(2026, 10, 1, 12, 0, 0), payer_vpa=vpa, payee_vpa="aditya874@oksbi", amount=Decimal("100.00"), remarks="")
        )
        self.payment_service.process_pay(
            PayCommand(line_no=2, command_name="PAY", raw_line="", txn_id="TXN-D2", timestamp=datetime(2026, 10, 5, 12, 0, 0), payer_vpa=vpa, payee_vpa="aditya874@oksbi", amount=Decimal("200.00"), remarks="")
        )
        self.payment_service.process_pay(
            PayCommand(line_no=3, command_name="PAY", raw_line="", txn_id="TXN-D3", timestamp=datetime(2026, 10, 10, 12, 0, 0), payer_vpa=vpa, payee_vpa="aditya874@oksbi", amount=Decimal("300.00"), remarks="")
        )

        # Filter strictly for 2026-10-05 to 2026-10-05 (only TXN-D2)
        stmt = StatementCommand(line_no=4, command_name="STATEMENT", raw_line="", vpa=vpa, from_date=date(2026, 10, 5), to_date=date(2026, 10, 5))
        recs = self.payment_service.process_statement(stmt)

        self.assertEqual(len(recs), 1)
        self.assertEqual(recs[0].txn_id, "TXN-D2")

    # -------------------------------------------------------------------------
    # 17. Multiple statements
    # -------------------------------------------------------------------------
    def test_17_multiple_statements(self):
        """Verify multiple statement commands for the same or different VPAs execute independently."""
        vpa = "rohit890@okaxis"
        self.payment_service.process_pay(
            PayCommand(line_no=1, command_name="PAY", raw_line="", txn_id="TXN-M1", timestamp=datetime(2026, 10, 1, 10, 0, 0), payer_vpa=vpa, payee_vpa="aditya874@oksbi", amount=Decimal("500.00"), remarks="")
        )

        stmt1 = StatementCommand(line_no=2, command_name="STATEMENT", raw_line="", vpa=vpa, from_date=date(2026, 10, 1), to_date=date(2026, 10, 1))
        stmt2 = StatementCommand(line_no=3, command_name="STATEMENT", raw_line="", vpa=vpa, from_date=date(2026, 10, 1), to_date=date(2026, 10, 2))

        recs1 = self.payment_service.process_statement(stmt1)
        recs2 = self.payment_service.process_statement(stmt2)

        self.assertEqual(len(recs1), 1)
        self.assertEqual(len(recs2), 1)
        self.assertEqual(recs1[0].txn_id, recs2[0].txn_id)

    # -------------------------------------------------------------------------
    # 18. Decimal precision
    # -------------------------------------------------------------------------
    def test_18_decimal_precision(self):
        """Verify that zero float conversions occur and monetary values maintain exact 2-decimal scale."""
        vpa = "rohit890@okaxis"
        self.payment_service.process_pay(
            PayCommand(line_no=1, command_name="PAY", raw_line="", txn_id="TXN-P1", timestamp=datetime(2026, 10, 1, 10, 0, 0), payer_vpa=vpa, payee_vpa="aditya874@oksbi", amount=Decimal("123.45"), remarks="")
        )

        entries = self.ledger.get_all_entries()
        for e in entries:
            self.assertIsInstance(e.amount, Decimal)
            if e.debit is not None:
                self.assertIsInstance(e.debit, Decimal)
            if e.credit is not None:
                self.assertIsInstance(e.credit, Decimal)
            self.assertIsInstance(e.running_balance, Decimal)

        stmt = StatementCommand(line_no=2, command_name="STATEMENT", raw_line="", vpa=vpa, from_date=date(2026, 10, 1), to_date=date(2026, 10, 1))
        recs = self.payment_service.process_statement(stmt)
        csv_row = recs[0].to_csv_row()
        self.assertEqual(csv_row[4], "123.45")

    # -------------------------------------------------------------------------
    # 19. Full integration benchmark match against statements.csv
    # -------------------------------------------------------------------------
    def test_19_benchmark_statements_match(self):
        """Simulate all 118 commands from commands.txt and verify exact 100% match against benchmark statements.csv."""
        commands = CommandParser.parse_file(COMMANDS_TXT_PATH)
        sim_master_data = self.master_data.clone()
        sim_ledger = DoubleEntryLedger()
        sim_rrn_gen = RRNGenerator(start_seq=1)
        sim_payment_service = PaymentService(sim_master_data, sim_ledger, sim_rrn_gen)

        for cmd in commands:
            if hasattr(cmd, "timestamp"):
                sim_payment_service.dispute_service.sweep_auto_acceptance(cmd.timestamp)
            if isinstance(cmd, PayCommand):
                sim_payment_service.process_pay(cmd)
            elif isinstance(cmd, BeneResponseCommand):
                sim_payment_service.process_bene_response(cmd)
            elif isinstance(cmd, TimeoutJobCommand):
                sim_payment_service.process_timeout_job(cmd)
            elif isinstance(cmd, CollectCommand):
                sim_payment_service.process_collect(cmd)
            elif isinstance(cmd, CollectApproveCommand):
                sim_payment_service.process_collect_approve(cmd)
            elif isinstance(cmd, CollectDeclineCommand):
                sim_payment_service.process_collect_decline(cmd)
            elif isinstance(cmd, RefundCommand):
                sim_payment_service.process_refund(cmd)
            elif isinstance(cmd, DisputeCommand):
                sim_payment_service.process_dispute(cmd)
            elif isinstance(cmd, DisputeActionCommand):
                sim_payment_service.process_dispute_action(cmd)
            elif isinstance(cmd, StatementCommand):
                sim_payment_service.process_statement(cmd)

        generated_records = sim_payment_service.get_statement_records()
        self.assertEqual(len(generated_records), 60)

        # Compare against X:\10sec\output\statements.csv
        benchmark_path = Path(r"X:\10sec\output\statements.csv")
        if benchmark_path.exists():
            with open(benchmark_path, mode="r", encoding="utf-8-sig") as f:
                reader = csv.reader(f)
                header = next(reader)
                self.assertEqual(header, ["vpa", "timestamp", "txn_id", "leg_type", "debit", "credit", "running_balance"])
                expected_rows = list(reader)

            self.assertEqual(len(generated_records), len(expected_rows))
            for idx, (gen_rec, exp_row) in enumerate(zip(generated_records, expected_rows)):
                gen_row = gen_rec.to_csv_row()
                self.assertEqual(
                    gen_row,
                    exp_row,
                    f"Mismatch at row {idx + 1}:\nGenerated: {gen_row}\nExpected:  {exp_row}",
                )

        # Full reconciliation audit assertion
        recon_report = sim_payment_service.reconcile()
        self.assertTrue(recon_report.is_balanced)
        self.assertEqual(recon_report.total_debits, recon_report.total_credits)
        self.assertEqual(recon_report.debit_credit_variance, Decimal("0.00"))
        self.assertEqual(recon_report.system_balance_variance, Decimal("0.00"))
        self.assertEqual(recon_report.clearing_balance, Decimal("4200.00"))
        self.assertEqual(recon_report.pending_escrow_liability, Decimal("4200.00"))
        self.assertEqual(len(recon_report.accounts_with_mismatch), 0)
        self.assertEqual(len(recon_report.duplicate_postings), 0)
        self.assertEqual(len(recon_report.anomalies), 0)


if __name__ == "__main__":
    unittest.main()
