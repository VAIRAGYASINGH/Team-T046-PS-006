"""Configuration and regulatory constants for PS-006 UPI Payment Switch."""

from decimal import Decimal
from pathlib import Path

# Base directories
BASE_DIR = Path(__file__).resolve().parent.parent
SAMPLE_DATA_DIR = BASE_DIR / "sample-data"
SAMPLE_DATA_PACK_DIR = SAMPLE_DATA_DIR / "T046-PS-006-sample-data"
OUTPUT_DIR = BASE_DIR / "output"

def get_data_file_path(filename: str) -> Path:
    """Resolve data file path checking sample-data root then sample-data pack directory."""
    primary = SAMPLE_DATA_DIR / filename
    if primary.exists():
        return primary
    secondary = SAMPLE_DATA_PACK_DIR / filename
    if secondary.exists():
        return secondary
    return primary

# File paths
USERS_CSV_PATH = get_data_file_path("users.csv")
MERCHANTS_CSV_PATH = get_data_file_path("merchants.csv")
HOLIDAYS_CSV_PATH = get_data_file_path("holidays.csv")
COMMANDS_TXT_PATH = get_data_file_path("commands.txt")

# Regulatory Limits (BR-01, BR-02, BR-03, BR-04, BR-07)
P2P_MAX_AMOUNT = Decimal("100000.00")
P2M_VERIFIED_MAX_AMOUNT = Decimal("200000.00")
P2M_UNVERIFIED_MAX_AMOUNT = Decimal("100000.00")
DAILY_DEBIT_AMOUNT_CAP = Decimal("100000.00")
DAILY_DEBIT_COUNT_CAP = 20

# Cooling Period Caps (BR-04)
COOLING_PERIOD_HOURS = 24
COOLING_PERIOD_MAX_AMOUNT = Decimal("5000.00")
NEW_BENEFICIARY_MAX_COUNT_IN_COOLING = 3

# Collect Limits (BR-07)
COLLECT_PIN_THRESHOLD = Decimal("2000.00")
COLLECT_MAX_EXPIRY_MINUTES = 4320  # 72 hours

# Timeout & SLA Windows (BR-05, BR-08, BR-09, BR-10)
TIMEOUT_REVERSAL_MINUTES = 30
TCC_CALENDAR_DAYS = 5
CHARGEBACK_CALENDAR_DAYS = 45
REPRESENTMENT_WORKING_DAYS = 5
PRE_ARBITRATION_CALENDAR_DAYS = 7
