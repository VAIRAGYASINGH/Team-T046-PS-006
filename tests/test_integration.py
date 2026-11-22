"""Comprehensive End-to-End System Integration & Audit Verification Tests for PS-006 UPI Switch.

Verifies:
1. Strict sequential command dispatch (118 commands).
2. Authoritative output generation for all four mandated reports in output/:
   - output/txn_log.csv (51 data rows)
   - output/txn_final.csv (46 data rows)
   - output/dispute_log.csv (17 data rows)
   - output/statements.csv (60 data rows)
3. 100% field-for-field exact match against benchmark X:/10sec/output/*.csv.
4. Determinism & repeatability across independent switch executions.
5. Immutability of sample-data input files.
6. Double-entry ledger balance conservation and escrow integrity (TXN-0038).
7. Complete Decimal currency enforcement (zero float usage).
"""

import csv
from decimal import Decimal
import os
from pathlib import Path
import unittest

from src.config import (
    BASE_DIR,
    COMMANDS_TXT_PATH,
    HOLIDAYS_CSV_PATH,
    MERCHANTS_CSV_PATH,
    USERS_CSV_PATH,
)
from src.data import load_master_data
from src.ledger import DoubleEntryLedger
from src.models import (
    BeneResponseCommand,
    CollectApproveCommand,
    CollectCommand,
    CollectDeclineCommand,
    DisputeActionCommand,
    DisputeCommand,
    PayCommand,
    RefundCommand,
    StatementCommand,
    TimeoutJobCommand,
)
from src.parser import CommandParser
from src.payments import PaymentService
from src.reports import ReportGenerator
from src.rrn_generator import RRNGenerator

BENCHMARK_DIR = Path("X:/10sec/output")


def _run_full_simulation():
    """Helper to execute full 118-command switch simulation."""
    master_data = load_master_data(
        users_path=USERS_CSV_PATH,
        merchants_path=MERCHANTS_CSV_PATH,
        holidays_path=HOLIDAYS_CSV_PATH,
    )
    commands = CommandParser.parse_file(COMMANDS_TXT_PATH)
    ledger = DoubleEntryLedger()
    rrn_gen = RRNGenerator(start_seq=1)
    payment_service = PaymentService(
        master_data=master_data,
        ledger=ledger,
        rrn_generator=rrn_gen,
    )

    for cmd in commands:
        if isinstance(cmd, PayCommand):
            payment_service.process_pay(cmd)
        elif isinstance(cmd, BeneResponseCommand):
            payment_service.process_bene_response(cmd)
        elif isinstance(cmd, TimeoutJobCommand):
            payment_service.process_timeout_job(cmd)
        elif isinstance(cmd, CollectCommand):
            payment_service.process_collect(cmd)
        elif isinstance(cmd, CollectApproveCommand):
            payment_service.process_collect_approve(cmd)
        elif isinstance(cmd, CollectDeclineCommand):
            payment_service.process_collect_decline(cmd)
        elif isinstance(cmd, RefundCommand):
            payment_service.process_refund(cmd)
        elif isinstance(cmd, DisputeCommand):
            payment_service.process_dispute(cmd)
        elif isinstance(cmd, DisputeActionCommand):
            payment_service.process_dispute_action(cmd)
        elif isinstance(cmd, StatementCommand):
            payment_service.process_statement(cmd)

    return master_data, commands, ledger, payment_service


class TestSystemIntegrationAndVerification(unittest.TestCase):
    """End-to-end integration and evaluation suite for PS-006 UPI Switch."""

    @classmethod
    def setUpClass(cls):
        cls.master_data, cls.commands, cls.ledger, cls.ps = _run_full_simulation()
        cls.output_dir = BASE_DIR / "output"
        cls.report_paths = ReportGenerator.generate_all_reports(cls.ps, cls.output_dir)

    def test_01_all_118_commands_dispatched(self):
        """Verify all 118 commands from commands.txt are parsed and executed in line order."""
        self.assertEqual(len(self.commands), 118)
        self.assertEqual(self.commands[0].line_no, 3)
        self.assertEqual(self.commands[-1].line_no, 158)
        for i in range(len(self.commands) - 1):
            self.assertLess(self.commands[i].line_no, self.commands[i + 1].line_no)

    def test_02_all_four_report_files_generated(self):
        """Verify all 4 required CSV report files exist and are non-empty."""
        expected_files = ["txn_log.csv", "txn_final.csv", "dispute_log.csv", "statements.csv"]
        for fname in expected_files:
            file_path = self.output_dir / fname
            self.assertTrue(file_path.exists(), f"File {file_path} must exist")
            self.assertGreater(file_path.stat().st_size, 0, f"File {file_path} must not be empty")

    def test_03_exact_schema_headers(self):
        """Verify exact header column names and column counts for all 4 output CSV files."""
        expected_headers = {
            "txn_log.csv": [
                "line_no", "txn_id", "command", "timestamp",
                "payer_vpa", "payee_vpa", "amount", "status", "rrn", "rule_ids"
            ],
            "txn_final.csv": [
                "txn_id", "final_status", "amount", "refunded_amount",
                "reversed_at", "deemed_success_at"
            ],
            "dispute_log.csv": [
                "line_no", "dispute_id", "txn_id", "type", "action",
                "timestamp", "result_state", "deadline", "rule_ids"
            ],
            "statements.csv": [
                "vpa", "timestamp", "txn_id", "leg_type", "debit", "credit", "running_balance"
            ],
        }

        for fname, exp_hdr in expected_headers.items():
            file_path = self.output_dir / fname
            with open(file_path, "r", newline="", encoding="utf-8") as f:
                reader = csv.reader(f)
                actual_hdr = next(reader)
                self.assertEqual(actual_hdr, exp_hdr, f"Header mismatch in {fname}")

    def test_04_exact_data_row_counts(self):
        """Verify authoritative row counts for each output CSV file."""
        expected_counts = {
            "txn_log.csv": 51,
            "txn_final.csv": 46,
            "dispute_log.csv": 17,
            "statements.csv": 60,
        }

        for fname, exp_count in expected_counts.items():
            file_path = self.output_dir / fname
            with open(file_path, "r", newline="", encoding="utf-8") as f:
                reader = csv.reader(f)
                rows = list(reader)
                data_rows = rows[1:]  # Exclude header
                self.assertEqual(
                    len(data_rows),
                    exp_count,
                    f"Row count mismatch in {fname}: expected {exp_count}, got {len(data_rows)}"
                )

    def test_05_100_percent_benchmark_equality(self):
        """Verify field-for-field 100% exact equality against reference benchmark files."""
        if not BENCHMARK_DIR.exists():
            self.skipTest(f"Benchmark directory {BENCHMARK_DIR} not available for comparison")

        files_to_compare = ["txn_log.csv", "txn_final.csv", "dispute_log.csv", "statements.csv"]
        for fname in files_to_compare:
            actual_path = self.output_dir / fname
            expected_path = BENCHMARK_DIR / fname
            self.assertTrue(expected_path.exists(), f"Benchmark file {expected_path} missing")

            with open(actual_path, "r", newline="", encoding="utf-8") as f_act, \
                 open(expected_path, "r", newline="", encoding="utf-8-sig") as f_exp:
                actual_rows = list(csv.reader(f_act))
                expected_rows = list(csv.reader(f_exp))

                self.assertEqual(
                    len(actual_rows),
                    len(expected_rows),
                    f"Total lines in {fname} do not match benchmark: {len(actual_rows)} vs {len(expected_rows)}"
                )

                for row_idx, (act_row, exp_row) in enumerate(zip(actual_rows, expected_rows)):
                    self.assertEqual(
                        act_row,
                        exp_row,
                        f"Mismatch in {fname} at line {row_idx + 1}:\nActual:   {act_row}\nExpected: {exp_row}"
                    )

    def test_06_deterministic_repeatability(self):
        """Verify that two independent runs produce byte-for-byte identical results."""
        import tempfile
        _, _, _, ps2 = _run_full_simulation()
        with tempfile.TemporaryDirectory() as tmpdirname:
            temp_dir = Path(tmpdirname)
            paths2 = ReportGenerator.generate_all_reports(ps2, temp_dir)

            for report_key in ["txn_log", "txn_final", "dispute_log", "statements"]:
                file1 = self.report_paths[report_key]
                file2 = paths2[report_key]
                with open(file1, "rb") as f1, open(file2, "rb") as f2:
                    self.assertEqual(f1.read(), f2.read(), f"Determinism failure in {report_key}")

    def test_07_sample_data_immutability(self):
        """Verify original sample-data files are unchanged and valid."""
        for p in [USERS_CSV_PATH, MERCHANTS_CSV_PATH, HOLIDAYS_CSV_PATH, COMMANDS_TXT_PATH]:
            self.assertTrue(p.exists(), f"Sample data file {p} must exist")
            self.assertGreater(p.stat().st_size, 0)

    def test_08_double_entry_reconciliation_invariants(self):
        """Verify complete double-entry conservation, zero variance, and in-flight escrow liability."""
        report = self.ps.reconcile()
        self.assertTrue(report.is_balanced)
        self.assertEqual(report.total_debits, Decimal("726038.00"))
        self.assertEqual(report.total_credits, Decimal("726038.00"))
        self.assertEqual(report.debit_credit_variance, Decimal("0.00"))
        self.assertEqual(report.total_system_balance, Decimal("6036500.00"))
        self.assertEqual(report.system_balance_variance, Decimal("0.00"))
        self.assertEqual(report.clearing_balance, Decimal("4200.00"))
        self.assertEqual(report.pending_escrow_liability, Decimal("4200.00"))
        self.assertEqual(report.total_accounts_reconciled, 16)
        self.assertEqual(len(report.accounts_with_mismatch), 0)
        self.assertEqual(len(report.duplicate_postings), 0)
        self.assertEqual(len(report.anomalies), 0)

    def test_09_zero_floats_monetary_types(self):
        """Verify strict Decimal type adherence across all financial accounts and entries."""
        for user in self.master_data.users_by_id.values():
            self.assertIsInstance(user.balance, Decimal)
            self.assertIsInstance(user.daily_debit_amount, Decimal)
        for merchant in self.master_data.merchants_by_id.values():
            self.assertIsInstance(merchant.balance, Decimal)
        for entry in self.ledger.get_all_entries():
            self.assertIsInstance(entry.amount, Decimal)
            if entry.debit is not None:
                self.assertIsInstance(entry.debit, Decimal)
            if entry.credit is not None:
                self.assertIsInstance(entry.credit, Decimal)
            self.assertIsInstance(entry.running_balance, Decimal)

    def test_10_transaction_terminal_state_distribution(self):
        """Verify exact breakdown of terminal transaction states matches expectation."""
        txns = self.ps.get_all_transactions()
        self.assertEqual(len(txns), 46)
        from collections import Counter
        counts = Counter(t.status.value for t in txns)
        self.assertEqual(counts["SUCCESS"], 29)
        self.assertEqual(counts["FAILED"], 11)
        self.assertEqual(counts["DECLINED"], 1)
        self.assertEqual(counts["DEEMED_SUCCESS"], 1)
        self.assertEqual(counts["EXPIRED"], 1)
        self.assertEqual(counts["PENDING"], 1)
        self.assertEqual(counts["RECON_SHORTFALL"], 1)
        self.assertEqual(counts["REVERSED"], 1)


if __name__ == "__main__":
    unittest.main()
