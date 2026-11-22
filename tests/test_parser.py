"""Tests for Phase 1 Command Parser (commands.txt)."""

from datetime import date, datetime
from decimal import Decimal
import tempfile
import unittest

from src.config import COMMANDS_TXT_PATH
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
    RefundCommand,
    StatementCommand,
    TimeoutJobCommand,
)
from src.parser import CommandParseError, CommandParser


class TestCommandParser(unittest.TestCase):
    """Unit tests for commands.txt parsing and validation."""

    def test_command_count(self):
        """Verify that exactly 118 valid non-comment commands are parsed from commands.txt."""
        commands = CommandParser.parse_file(COMMANDS_TXT_PATH)
        self.assertEqual(len(commands), 118)

    def test_command_order_strictly_preserved(self):
        """Verify that commands are in strict physical file order and not reordered."""
        commands = CommandParser.parse_file(COMMANDS_TXT_PATH)
        for i in range(len(commands) - 1):
            self.assertLess(
                commands[i].line_no,
                commands[i + 1].line_no,
                f"Line numbers out of order: {commands[i].line_no} >= {commands[i + 1].line_no}",
            )

    def test_physical_line_numbers(self):
        """Verify exact physical line numbers for landmark commands in commands.txt."""
        commands = CommandParser.parse_file(COMMANDS_TXT_PATH)
        cmd_by_line = {cmd.line_no: cmd for cmd in commands}

        # Line 3: PAY|TXN-0001|2026-10-01 09:10:00|rohit890@okaxis|aditya874@oksbi|8000.00|bill
        cmd3 = cmd_by_line.get(3)
        self.assertIsInstance(cmd3, PayCommand)
        self.assertEqual(cmd3.txn_id, "TXN-0001")
        self.assertEqual(cmd3.timestamp, datetime(2026, 10, 1, 9, 10, 0))
        self.assertEqual(cmd3.payer_vpa, "rohit890@okaxis")
        self.assertEqual(cmd3.payee_vpa, "aditya874@oksbi")
        self.assertEqual(cmd3.amount, Decimal("8000.00"))
        self.assertEqual(cmd3.remarks, "bill")

        # Line 4: BENE_RESPONSE|TXN-0001|2026-10-01 09:10:03|ACK
        cmd4 = cmd_by_line.get(4)
        self.assertIsInstance(cmd4, BeneResponseCommand)
        self.assertEqual(cmd4.txn_id, "TXN-0001")
        self.assertEqual(cmd4.response, BeneResponseType.ACK)

        # Line 35: TIMEOUT_JOB|2026-10-08 10:30:00
        cmd35 = cmd_by_line.get(35)
        self.assertIsInstance(cmd35, TimeoutJobCommand)
        self.assertEqual(cmd35.timestamp, datetime(2026, 10, 8, 10, 30, 0))

        # Line 49: COLLECT|TXN-0019|2026-10-11 12:00:00|udupi@icici|geetha308@ybl|2000.00|30|false
        cmd49 = cmd_by_line.get(49)
        self.assertIsInstance(cmd49, CollectCommand)
        self.assertEqual(cmd49.txn_id, "TXN-0019")
        self.assertEqual(cmd49.requester_vpa, "udupi@icici")
        self.assertEqual(cmd49.payer_vpa, "geetha308@ybl")
        self.assertEqual(cmd49.amount, Decimal("2000.00"))
        self.assertEqual(cmd49.expiry_minutes, 30)
        self.assertFalse(cmd49.pin_flag)

        # Line 50: COLLECT_APPROVE|TXN-0019|2026-10-11 12:05:00
        cmd50 = cmd_by_line.get(50)
        self.assertIsInstance(cmd50, CollectApproveCommand)
        self.assertEqual(cmd50.txn_id, "TXN-0019")

        # Line 56: COLLECT_DECLINE|TXN-0023|2026-10-11 14:10:00
        cmd56 = cmd_by_line.get(56)
        self.assertIsInstance(cmd56, CollectDeclineCommand)
        self.assertEqual(cmd56.txn_id, "TXN-0023")

        # Line 58: DISPUTE|DSP-001|2026-10-12 10:00:00|TXN-0016|CHARGEBACK|REMITTER|450.00
        cmd58 = cmd_by_line.get(58)
        self.assertIsInstance(cmd58, DisputeCommand)
        self.assertEqual(cmd58.dispute_id, "DSP-001")
        self.assertEqual(cmd58.txn_id, "TXN-0016")
        self.assertEqual(cmd58.dispute_type, DisputeType.CHARGEBACK)
        self.assertEqual(cmd58.raised_by, "REMITTER")
        self.assertEqual(cmd58.amount, Decimal("450.00"))

        # Line 73: REFUND|TXN-0027|2026-10-17 10:00:00|300.00
        cmd73 = cmd_by_line.get(73)
        self.assertIsInstance(cmd73, RefundCommand)
        self.assertEqual(cmd73.txn_id, "TXN-0027")
        self.assertEqual(cmd73.amount, Decimal("300.00"))

        # Line 83: DISPUTE_ACTION|DSP-005|2026-10-21 10:00:00|ACCEPT
        cmd83 = cmd_by_line.get(83)
        self.assertIsInstance(cmd83, DisputeActionCommand)
        self.assertEqual(cmd83.dispute_id, "DSP-005")
        self.assertEqual(cmd83.action, DisputeAction.ACCEPT)

        # Line 158: STATEMENT|keerthi335@okaxis|2026-10-01|2026-11-19
        cmd158 = cmd_by_line.get(158)
        self.assertIsInstance(cmd158, StatementCommand)
        self.assertEqual(cmd158.vpa, "keerthi335@okaxis")
        self.assertEqual(cmd158.from_date, date(2026, 10, 1))
        self.assertEqual(cmd158.to_date, date(2026, 11, 19))

    def test_comments_and_blank_lines_handling(self):
        """Verify that comments starting with '#' and blank lines are ignored while preserving line numbers."""
        content = """# Comment line 1
# Comment line 2
PAY|TXN-0001|2026-10-01 09:10:00|rohit@psp|aditya@psp|100.00|test

# Another comment on line 5

TIMEOUT_JOB|2026-10-01 10:00:00
"""
        with tempfile.NamedTemporaryFile("w+", delete=False, suffix=".txt") as f:
            f.write(content)
            path = f.name

        commands = CommandParser.parse_file(path)
        self.assertEqual(len(commands), 2)
        self.assertEqual(commands[0].line_no, 3)
        self.assertEqual(commands[0].command_name, "PAY")
        self.assertEqual(commands[1].line_no, 7)
        self.assertEqual(commands[1].command_name, "TIMEOUT_JOB")

    def test_comparison_against_raw_commands_file(self):
        """Verify parsed command raw_line matches original file content exactly."""
        commands = CommandParser.parse_file(COMMANDS_TXT_PATH)
        with open(COMMANDS_TXT_PATH, "r", encoding="utf-8") as f:
            raw_lines = f.readlines()

        for cmd in commands:
            expected_raw = raw_lines[cmd.line_no - 1].rstrip("\r\n")
            self.assertEqual(
                cmd.raw_line,
                expected_raw,
                f"Line {cmd.line_no} mismatch: expected '{expected_raw}', got '{cmd.raw_line}'",
            )

    def test_invalid_command_name(self):
        """Verify that an unknown command name raises CommandParseError."""
        with self.assertRaises(CommandParseError):
            CommandParser.parse_line(10, "INVALID_CMD|arg1|arg2")

    def test_invalid_argument_count(self):
        """Verify that missing or excess arguments raise CommandParseError."""
        # PAY expects 6 args
        with self.assertRaises(CommandParseError):
            CommandParser.parse_line(1, "PAY|TXN-0001|2026-10-01 10:00:00|payer|payee")

        with self.assertRaises(CommandParseError):
            CommandParser.parse_line(2, "TIMEOUT_JOB|2026-10-01 10:00:00|extra_arg")

    def test_invalid_timestamp_format(self):
        """Verify that malformed timestamp raises CommandParseError."""
        with self.assertRaises(CommandParseError):
            CommandParser.parse_line(1, "TIMEOUT_JOB|2026/10/01-10:00")

    def test_invalid_date_format(self):
        """Verify that malformed date raises CommandParseError."""
        with self.assertRaises(CommandParseError):
            CommandParser.parse_line(1, "STATEMENT|vpa@psp|01-10-2026|19-11-2026")

    def test_invalid_decimal_format(self):
        """Verify that malformed decimal raises CommandParseError."""
        with self.assertRaises(CommandParseError):
            CommandParser.parse_line(1, "REFUND|TXN-0001|2026-10-01 10:00:00|INVALID_DECIMAL")

    def test_invalid_boolean_format(self):
        """Verify that malformed boolean raises CommandParseError."""
        with self.assertRaises(CommandParseError):
            CommandParser.parse_line(1, "COLLECT|TXN-0001|2026-10-01 10:00:00|req|pay|100.00|30|MAYBE")

    def test_invalid_bene_response_value(self):
        """Verify that invalid BENE_RESPONSE status raises CommandParseError."""
        with self.assertRaises(CommandParseError):
            CommandParser.parse_line(1, "BENE_RESPONSE|TXN-0001|2026-10-01 10:00:00|MAYBE")

    def test_invalid_dispute_type(self):
        """Verify that invalid dispute type raises CommandParseError."""
        with self.assertRaises(CommandParseError):
            CommandParser.parse_line(1, "DISPUTE|DSP-001|2026-10-01 10:00:00|TXN-0001|INVALID|REMITTER|100.00")


if __name__ == "__main__":
    unittest.main()
