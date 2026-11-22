"""Event log parser for commands.txt in the PS-006 UPI Switch."""

from datetime import datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Iterator, List, Union

from src.config import COMMANDS_TXT_PATH
from src.models import (
    BeneResponseCommand,
    BeneResponseType,
    CollectApproveCommand,
    CollectCommand,
    CollectDeclineCommand,
    Command,
    DisputeAction,
    DisputeActionCommand,
    DisputeCommand,
    DisputeType,
    PayCommand,
    RefundCommand,
    StatementCommand,
    TimeoutJobCommand,
)


class CommandParseError(Exception):
    """Raised when parsing a command line from commands.txt fails."""
    def __init__(self, line_no: int, message: str, raw_line: str = ""):
        self.line_no = line_no
        self.raw_line = raw_line
        super().__init__(f"Line {line_no}: {message} [raw: '{raw_line.strip()}']")


class CommandParser:
    """Parses commands.txt preserving physical line numbers and file order."""

    DATETIME_FORMAT = "%Y-%m-%d %H:%M:%S"
    DATE_FORMAT = "%Y-%m-%d"

    SUPPORTED_COMMANDS = {
        "PAY": 6,
        "BENE_RESPONSE": 3,
        "TIMEOUT_JOB": 1,
        "COLLECT": 7,
        "COLLECT_APPROVE": 2,
        "COLLECT_DECLINE": 2,
        "REFUND": 3,
        "DISPUTE": 6,
        "DISPUTE_ACTION": 3,
        "STATEMENT": 3,
    }

    @staticmethod
    def _parse_timestamp(val: str, line_no: int, field_name: str) -> datetime:
        try:
            return datetime.strptime(val.strip(), CommandParser.DATETIME_FORMAT)
        except ValueError as e:
            raise CommandParseError(line_no, f"Invalid timestamp format for '{field_name}': '{val}'") from e

    @staticmethod
    def _parse_date(val: str, line_no: int, field_name: str) -> datetime.date:
        try:
            return datetime.strptime(val.strip(), CommandParser.DATE_FORMAT).date()
        except ValueError as e:
            raise CommandParseError(line_no, f"Invalid date format for '{field_name}': '{val}'") from e

    @staticmethod
    def _parse_decimal(val: str, line_no: int, field_name: str) -> Decimal:
        try:
            amount = Decimal(val.strip())
            return amount
        except (InvalidOperation, ValueError) as e:
            raise CommandParseError(line_no, f"Invalid decimal amount for '{field_name}': '{val}'") from e

    @staticmethod
    def _parse_bool(val: str, line_no: int, field_name: str) -> bool:
        norm = val.strip().lower()
        if norm == "true":
            return True
        elif norm == "false":
            return False
        raise CommandParseError(line_no, f"Invalid boolean value for '{field_name}': '{val}'")

    @classmethod
    def parse_line(cls, line_no: int, raw_line: str) -> Command:
        """Parse a single command line preserving line_no."""
        clean_line = raw_line.rstrip("\r\n")
        parts = clean_line.split("|")
        cmd_name = parts[0].strip()

        if cmd_name not in cls.SUPPORTED_COMMANDS:
            raise CommandParseError(line_no, f"Unsupported command '{cmd_name}'", clean_line)

        expected_args = cls.SUPPORTED_COMMANDS[cmd_name]
        actual_args = len(parts) - 1
        if actual_args != expected_args:
            raise CommandParseError(
                line_no,
                f"Command '{cmd_name}' expects {expected_args} arguments, but received {actual_args}",
                clean_line,
            )

        args = [p.strip() for p in parts[1:]]

        if cmd_name == "PAY":
            return PayCommand(
                line_no=line_no,
                command_name=cmd_name,
                raw_line=clean_line,
                txn_id=args[0],
                timestamp=cls._parse_timestamp(args[1], line_no, "timestamp"),
                payer_vpa=args[2],
                payee_vpa=args[3],
                amount=cls._parse_decimal(args[4], line_no, "amount"),
                remarks=args[5],
            )

        elif cmd_name == "BENE_RESPONSE":
            resp_str = args[2].upper()
            if resp_str not in ("ACK", "NACK", "NO_RESPONSE"):
                raise CommandParseError(
                    line_no, f"Invalid BENE_RESPONSE value '{args[2]}'; must be ACK, NACK, or NO_RESPONSE", clean_line
                )
            return BeneResponseCommand(
                line_no=line_no,
                command_name=cmd_name,
                raw_line=clean_line,
                txn_id=args[0],
                timestamp=cls._parse_timestamp(args[1], line_no, "timestamp"),
                response=BeneResponseType(resp_str),
            )

        elif cmd_name == "TIMEOUT_JOB":
            return TimeoutJobCommand(
                line_no=line_no,
                command_name=cmd_name,
                raw_line=clean_line,
                timestamp=cls._parse_timestamp(args[0], line_no, "timestamp"),
            )

        elif cmd_name == "COLLECT":
            try:
                expiry = int(args[5])
            except ValueError as e:
                raise CommandParseError(line_no, f"Invalid integer for expiry_minutes: '{args[5]}'", clean_line) from e

            return CollectCommand(
                line_no=line_no,
                command_name=cmd_name,
                raw_line=clean_line,
                txn_id=args[0],
                timestamp=cls._parse_timestamp(args[1], line_no, "timestamp"),
                requester_vpa=args[2],
                payer_vpa=args[3],
                amount=cls._parse_decimal(args[4], line_no, "amount"),
                expiry_minutes=expiry,
                pin_flag=cls._parse_bool(args[6], line_no, "pin_flag"),
            )

        elif cmd_name == "COLLECT_APPROVE":
            return CollectApproveCommand(
                line_no=line_no,
                command_name=cmd_name,
                raw_line=clean_line,
                txn_id=args[0],
                timestamp=cls._parse_timestamp(args[1], line_no, "timestamp"),
            )

        elif cmd_name == "COLLECT_DECLINE":
            return CollectDeclineCommand(
                line_no=line_no,
                command_name=cmd_name,
                raw_line=clean_line,
                txn_id=args[0],
                timestamp=cls._parse_timestamp(args[1], line_no, "timestamp"),
            )

        elif cmd_name == "REFUND":
            return RefundCommand(
                line_no=line_no,
                command_name=cmd_name,
                raw_line=clean_line,
                txn_id=args[0],
                timestamp=cls._parse_timestamp(args[1], line_no, "timestamp"),
                amount=cls._parse_decimal(args[2], line_no, "amount"),
            )

        elif cmd_name == "DISPUTE":
            dtype_str = args[3].upper()
            if dtype_str not in ("TCC", "CHARGEBACK"):
                raise CommandParseError(
                    line_no, f"Invalid dispute type '{args[3]}'; must be TCC or CHARGEBACK", clean_line
                )
            raised_by = args[4].upper()
            if raised_by not in ("REMITTER", "BENEFICIARY_BANK"):
                raise CommandParseError(
                    line_no, f"Invalid dispute raised_by '{args[4]}'; must be REMITTER or BENEFICIARY_BANK", clean_line
                )
            return DisputeCommand(
                line_no=line_no,
                command_name=cmd_name,
                raw_line=clean_line,
                dispute_id=args[0],
                timestamp=cls._parse_timestamp(args[1], line_no, "timestamp"),
                txn_id=args[2],
                dispute_type=DisputeType(dtype_str),
                raised_by=raised_by,
                amount=cls._parse_decimal(args[5], line_no, "amount"),
            )

        elif cmd_name == "DISPUTE_ACTION":
            action_str = args[2].upper()
            if action_str not in ("ACCEPT", "REPRESENT", "PRE_ARBITRATION", "CLOSE"):
                raise CommandParseError(
                    line_no,
                    f"Invalid dispute action '{args[2]}'; must be ACCEPT, REPRESENT, PRE_ARBITRATION, or CLOSE",
                    clean_line,
                )
            return DisputeActionCommand(
                line_no=line_no,
                command_name=cmd_name,
                raw_line=clean_line,
                dispute_id=args[0],
                timestamp=cls._parse_timestamp(args[1], line_no, "timestamp"),
                action=DisputeAction(action_str),
            )

        elif cmd_name == "STATEMENT":
            return StatementCommand(
                line_no=line_no,
                command_name=cmd_name,
                raw_line=clean_line,
                vpa=args[0],
                from_date=cls._parse_date(args[1], line_no, "from_date"),
                to_date=cls._parse_date(args[2], line_no, "to_date"),
            )

        raise CommandParseError(line_no, f"Unhandled command '{cmd_name}'", clean_line)

    @classmethod
    def parse_file(cls, file_path: Union[str, Path] = COMMANDS_TXT_PATH) -> List[Command]:
        """Read and parse commands.txt strictly preserving file order and physical line numbers."""
        path = Path(file_path)
        if not path.exists():
            raise FileNotFoundError(f"Command file not found at: {path}")

        parsed_commands: List[Command] = []

        with open(path, mode="r", encoding="utf-8") as f:
            for physical_line_no, line in enumerate(f, start=1):
                stripped = line.strip()
                # Ignore empty lines and comment lines starting with '#'
                if not stripped or stripped.startswith("#"):
                    continue
                cmd = cls.parse_line(physical_line_no, line)
                parsed_commands.append(cmd)

        return parsed_commands
