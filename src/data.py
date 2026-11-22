"""Master data loader for users, merchants, and bank holidays."""

import csv
from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Dict, List, Optional, Union

from src.config import HOLIDAYS_CSV_PATH, MERCHANTS_CSV_PATH, USERS_CSV_PATH
from src.models import Holiday, Merchant, User


class DataLoadError(Exception):
    """Raised when loading or parsing master data CSV files fails."""
    pass


@dataclass
class MasterData:
    """Container holding loaded master data dictionaries and entities."""
    users_by_vpa: Dict[str, User] = field(default_factory=dict)
    users_by_id: Dict[str, User] = field(default_factory=dict)
    merchants_by_vpa: Dict[str, Merchant] = field(default_factory=dict)
    merchants_by_id: Dict[str, Merchant] = field(default_factory=dict)
    holidays_by_date: Dict[date, Holiday] = field(default_factory=dict)

    def get_user(self, vpa_or_id: str) -> Optional[User]:
        """Lookup user by VPA or user_id."""
        normalized = vpa_or_id.strip()
        return self.users_by_vpa.get(normalized) or self.users_by_id.get(normalized)

    def get_merchant(self, vpa_or_id: str) -> Optional[Merchant]:
        """Lookup merchant by VPA or merchant_id."""
        normalized = vpa_or_id.strip()
        return self.merchants_by_vpa.get(normalized) or self.merchants_by_id.get(normalized)

    def get_entity(self, vpa: str) -> Optional[Union[User, Merchant]]:
        """Lookup entity (User or Merchant) by VPA."""
        normalized = vpa.strip()
        user = self.users_by_vpa.get(normalized)
        if user is not None:
            return user
        return self.merchants_by_vpa.get(normalized)

    def is_holiday(self, target_date: date) -> bool:
        """Check if a date is listed as a bank holiday."""
        return target_date in self.holidays_by_date

    @property
    def holidays(self) -> List[Holiday]:
        """Return list of all registered holidays."""
        return list(self.holidays_by_date.values())

    def clone(self) -> "MasterData":
        """Produce a deep copy of the master dataset to allow fresh state for simulations."""
        cloned_users = [u.copy() for u in self.users_by_id.values()]
        cloned_merchants = [m.copy() for m in self.merchants_by_id.values()]
        return MasterData(
            users_by_vpa={u.vpa: u for u in cloned_users},
            users_by_id={u.user_id: u for u in cloned_users},
            merchants_by_vpa={m.vpa: m for m in cloned_merchants},
            merchants_by_id={m.merchant_id: m for m in cloned_merchants},
            holidays_by_date=dict(self.holidays_by_date),
        )


def load_users(csv_path: Union[str, Path] = USERS_CSV_PATH) -> tuple[Dict[str, User], Dict[str, User]]:
    """Load users from CSV into VPA and ID indexed dictionaries."""
    path = Path(csv_path)
    if not path.exists():
        raise DataLoadError(f"Users CSV file not found at: {path}")

    by_vpa: Dict[str, User] = {}
    by_id: Dict[str, User] = {}

    with open(path, mode="r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row_num, row in enumerate(reader, start=2):
            try:
                user = User(
                    user_id=row["user_id"].strip(),
                    name=row["name"].strip(),
                    vpa=row["vpa"].strip(),
                    bank_account=row["bank_account"].strip(),
                    ifsc=row["ifsc"].strip(),
                    balance=Decimal(row["balance"].strip()),
                    vpa_created_at=datetime.strptime(row["vpa_created_at"].strip(), "%Y-%m-%d %H:%M:%S"),
                    device_bound_at=datetime.strptime(row["device_bound_at"].strip(), "%Y-%m-%d %H:%M:%S"),
                    mobile=row["mobile"].strip(),
                    counter_date=datetime.strptime(row["counter_date"].strip(), "%Y-%m-%d").date(),
                    daily_debit_count=int(row["daily_debit_count"].strip()),
                    daily_debit_amount=Decimal(row["daily_debit_amount"].strip()),
                    monthly_debit_amount=Decimal(row["monthly_debit_amount"].strip()),
                )
                by_vpa[user.vpa] = user
                by_id[user.user_id] = user
            except (KeyError, ValueError, InvalidOperation) as err:
                raise DataLoadError(f"Error parsing users.csv at line {row_num}: {err}") from err

    return by_vpa, by_id


def load_merchants(csv_path: Union[str, Path] = MERCHANTS_CSV_PATH) -> tuple[Dict[str, Merchant], Dict[str, Merchant]]:
    """Load merchants from CSV into VPA and ID indexed dictionaries."""
    path = Path(csv_path)
    if not path.exists():
        raise DataLoadError(f"Merchants CSV file not found at: {path}")

    by_vpa: Dict[str, Merchant] = {}
    by_id: Dict[str, Merchant] = {}

    with open(path, mode="r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row_num, row in enumerate(reader, start=2):
            try:
                verified_str = row["verified"].strip().lower()
                verified = verified_str == "true"
                merchant = Merchant(
                    merchant_id=row["merchant_id"].strip(),
                    name=row["name"].strip(),
                    vpa=row["vpa"].strip(),
                    mcc=row["mcc"].strip(),
                    verified=verified,
                    bank_account=row["bank_account"].strip(),
                    balance=Decimal(row["balance"].strip()),
                )
                by_vpa[merchant.vpa] = merchant
                by_id[merchant.merchant_id] = merchant
            except (KeyError, ValueError, InvalidOperation) as err:
                raise DataLoadError(f"Error parsing merchants.csv at line {row_num}: {err}") from err

    return by_vpa, by_id


def load_holidays(csv_path: Union[str, Path] = HOLIDAYS_CSV_PATH) -> Dict[date, Holiday]:
    """Load bank holidays from CSV indexed by date."""
    path = Path(csv_path)
    if not path.exists():
        raise DataLoadError(f"Holidays CSV file not found at: {path}")

    holidays: Dict[date, Holiday] = {}

    with open(path, mode="r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row_num, row in enumerate(reader, start=2):
            try:
                h_date = datetime.strptime(row["date"].strip(), "%Y-%m-%d").date()
                name = row["name"].strip()
                holidays[h_date] = Holiday(date=h_date, name=name)
            except (KeyError, ValueError) as err:
                raise DataLoadError(f"Error parsing holidays.csv at line {row_num}: {err}") from err

    return holidays


def load_master_data(
    users_path: Union[str, Path] = USERS_CSV_PATH,
    merchants_path: Union[str, Path] = MERCHANTS_CSV_PATH,
    holidays_path: Union[str, Path] = HOLIDAYS_CSV_PATH,
) -> MasterData:
    """Load all master data files into a single MasterData container."""
    users_by_vpa, users_by_id = load_users(users_path)
    merchants_by_vpa, merchants_by_id = load_merchants(merchants_path)
    holidays_by_date = load_holidays(holidays_path)
    return MasterData(
        users_by_vpa=users_by_vpa,
        users_by_id=users_by_id,
        merchants_by_vpa=merchants_by_vpa,
        merchants_by_id=merchants_by_id,
        holidays_by_date=holidays_by_date,
    )
