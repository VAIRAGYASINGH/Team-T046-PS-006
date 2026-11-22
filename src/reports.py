"""Authoritative CSV report generation pipeline for PS-006 UPI Payment Switch.

Generates the four mandated audit and evaluation CSV files matching the specification:
1. output/txn_log.csv: Chronological transaction command events (51 rows)
2. output/txn_final.csv: Final terminal state of every unique transaction (46 rows)
3. output/dispute_log.csv: Chronological log of dispute filings and actions (17 rows)
4. output/statements.csv: Chronological double-entry customer statement legs (60 rows)
"""

import csv
from pathlib import Path
from typing import Dict, List, Union

from src.models import DisputeLogRow, StatementRecord, Transaction, TxnFinalRow, TxnLogRow


class ReportGenerator:
    """Generates the four authoritative CSV output reports required by PS-006."""

    @staticmethod
    def generate_txn_log_csv(rows: List[TxnLogRow], output_path: Union[str, Path]) -> Path:
        """Export transaction command event log matching txn_log.csv schema."""
        path = Path(output_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, mode="w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow([
                "line_no", "txn_id", "command", "timestamp",
                "payer_vpa", "payee_vpa", "amount", "status", "rrn", "rule_ids"
            ])
            for r in rows:
                writer.writerow(r.to_csv_row())
        return path

    @staticmethod
    def generate_txn_final_csv(transactions: List[Transaction], output_path: Union[str, Path]) -> Path:
        """Export terminal transaction states sorted by txn_id matching txn_final.csv schema."""
        path = Path(output_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        sorted_txns = sorted(transactions, key=lambda t: t.txn_id)
        with open(path, mode="w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow([
                "txn_id", "final_status", "amount", "refunded_amount",
                "reversed_at", "deemed_success_at"
            ])
            for t in sorted_txns:
                rev_str = t.reversed_at.strftime("%Y-%m-%d %H:%M:%S") if t.reversed_at else ""
                deemed_str = t.deemed_success_at.strftime("%Y-%m-%d %H:%M:%S") if t.deemed_success_at else ""
                status_str = t.status.value if hasattr(t.status, "value") else str(t.status)
                writer.writerow([
                    t.txn_id,
                    status_str,
                    f"{t.amount:.2f}",
                    f"{t.refunded_amount:.2f}",
                    rev_str,
                    deemed_str,
                ])
        return path

    @staticmethod
    def generate_dispute_log_csv(rows: List[DisputeLogRow], output_path: Union[str, Path]) -> Path:
        """Export dispute lifecycle events log matching dispute_log.csv schema."""
        path = Path(output_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, mode="w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow([
                "line_no", "dispute_id", "txn_id", "type", "action",
                "timestamp", "result_state", "deadline", "rule_ids"
            ])
            for r in rows:
                writer.writerow(r.to_csv_row())
        return path

    @staticmethod
    def generate_statements_csv(records: List[StatementRecord], output_path: Union[str, Path]) -> Path:
        """Export accumulated customer statement legs matching statements.csv schema."""
        path = Path(output_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, mode="w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow([
                "vpa", "timestamp", "txn_id", "leg_type", "debit", "credit", "running_balance"
            ])
            for rec in records:
                writer.writerow(rec.to_csv_row())
        return path

    @classmethod
    def generate_all_reports(cls, payment_service, output_dir: Union[str, Path]) -> Dict[str, Path]:
        """Generate all four official output CSV files in the designated directory."""
        out_dir = Path(output_dir)
        out_dir.mkdir(parents=True, exist_ok=True)

        txn_log_path = cls.generate_txn_log_csv(
            payment_service.get_txn_log_rows(), out_dir / "txn_log.csv"
        )
        txn_final_path = cls.generate_txn_final_csv(
            payment_service.get_all_transactions(), out_dir / "txn_final.csv"
        )
        dispute_log_path = cls.generate_dispute_log_csv(
            payment_service.get_dispute_log(), out_dir / "dispute_log.csv"
        )
        statements_path = cls.generate_statements_csv(
            payment_service.get_statement_records(), out_dir / "statements.csv"
        )

        return {
            "txn_log": txn_log_path,
            "txn_final": txn_final_path,
            "dispute_log": dispute_log_path,
            "statements": statements_path,
        }
