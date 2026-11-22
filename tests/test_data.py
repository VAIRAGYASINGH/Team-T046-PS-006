"""Tests for Phase 1 Master Data Loading (users.csv, merchants.csv, holidays.csv)."""

from datetime import date, datetime
from decimal import Decimal
import tempfile
import unittest

from src.data import (
    DataLoadError,
    load_holidays,
    load_master_data,
    load_merchants,
    load_users,
)
from src.models import Holiday, Merchant, User


class TestMasterDataLoading(unittest.TestCase):
    """Unit tests for master data loading and type conversions."""

    def test_user_count(self):
        """Verify that exactly 12 users are loaded from users.csv."""
        by_vpa, by_id = load_users()
        self.assertEqual(len(by_vpa), 12)
        self.assertEqual(len(by_id), 12)

    def test_merchant_count(self):
        """Verify that exactly 4 merchants are loaded from merchants.csv."""
        by_vpa, by_id = load_merchants()
        self.assertEqual(len(by_vpa), 4)
        self.assertEqual(len(by_id), 4)

    def test_holiday_loading(self):
        """Verify that bank holidays are loaded with correct dates and names."""
        holidays = load_holidays()
        self.assertEqual(len(holidays), 4)

        expected_holidays = {
            date(2026, 10, 2): "Gandhi Jayanti",
            date(2026, 10, 20): "Vijaya Dashami",
            date(2026, 11, 9): "Deepavali",
            date(2026, 11, 24): "Guru Nanak Jayanti",
        }
        for h_date, h_name in expected_holidays.items():
            self.assertIn(h_date, holidays)
            self.assertEqual(holidays[h_date].name, h_name)
            self.assertIsInstance(holidays[h_date], Holiday)

    def test_decimal_conversion(self):
        """Verify that all monetary values are Decimal instances, never float."""
        by_vpa, by_id = load_users()
        for user in by_id.values():
            self.assertIsInstance(user.balance, Decimal, f"User {user.user_id} balance is not Decimal")
            self.assertNotIsInstance(user.balance, float, f"User {user.user_id} balance is float")
            self.assertIsInstance(user.daily_debit_amount, Decimal)
            self.assertIsInstance(user.monthly_debit_amount, Decimal)
            self.assertIsInstance(user.daily_debit_count, int)
            self.assertIsInstance(user.vpa_created_at, datetime)
            self.assertIsInstance(user.counter_date, date)

        _, m_by_id = load_merchants()
        for m in m_by_id.values():
            self.assertIsInstance(m.balance, Decimal, f"Merchant {m.merchant_id} balance is not Decimal")
            self.assertNotIsInstance(m.balance, float, f"Merchant {m.merchant_id} balance is float")

    def test_merchant_boolean_conversion(self):
        """Verify that merchant verified field is converted to boolean correctly."""
        _, m_by_id = load_merchants()

        self.assertFalse(m_by_id["MER-001"].verified)
        self.assertTrue(m_by_id["MER-002"].verified)
        self.assertTrue(m_by_id["MER-003"].verified)
        self.assertFalse(m_by_id["MER-004"].verified)

        for m in m_by_id.values():
            self.assertIsInstance(m.verified, bool)

    def test_user_and_merchant_lookups(self):
        """Verify dictionary lookups by VPA and ID for users and merchants."""
        data = load_master_data()

        # User lookup
        rohit = data.get_user("rohit890@okaxis")
        self.assertIsNotNone(rohit)
        self.assertEqual(rohit.user_id, "USR-001")
        self.assertEqual(rohit.name, "Rohit DSouza")
        self.assertEqual(rohit.balance, Decimal("300000.00"))
        self.assertEqual(rohit.daily_debit_count, 6)
        self.assertEqual(rohit.daily_debit_amount, Decimal("82000.00"))

        # Merchant lookup
        metro = data.get_merchant("bengaluru@icici")
        self.assertIsNotNone(metro)
        self.assertEqual(metro.merchant_id, "MER-002")
        self.assertTrue(metro.verified)
        self.assertEqual(metro.balance, Decimal("900000.00"))

        # Entity lookup
        self.assertEqual(data.get_entity("rohit890@okaxis"), rohit)
        self.assertEqual(data.get_entity("bengaluru@icici"), metro)
        self.assertIsNone(data.get_entity("unknown@psp"))

    def test_cloning_isolation(self):
        """Verify that MasterData.clone() produces completely isolated mutable objects."""
        data = load_master_data()
        clone = data.clone()

        user_original = data.get_user("USR-001")
        user_clone = clone.get_user("USR-001")

        self.assertIsNot(user_original, user_clone)
        self.assertEqual(user_original.balance, user_clone.balance)

        # Mutate clone
        user_clone.balance += Decimal("1000.00")
        self.assertEqual(user_original.balance, Decimal("300000.00"))
        self.assertEqual(user_clone.balance, Decimal("301000.00"))

    def test_missing_file_raises_data_load_error(self):
        """Verify that non-existent CSV files raise DataLoadError."""
        with self.assertRaises(DataLoadError):
            load_users("non_existent_users.csv")

        with self.assertRaises(DataLoadError):
            load_merchants("non_existent_merchants.csv")

        with self.assertRaises(DataLoadError):
            load_holidays("non_existent_holidays.csv")

    def test_malformed_csv_raises_error(self):
        """Verify that corrupt CSV rows raise DataLoadError."""
        with tempfile.NamedTemporaryFile("w+", delete=False, suffix=".csv") as f:
            f.write("user_id,name,vpa,bank_account,ifsc,balance,vpa_created_at,device_bound_at,mobile,counter_date,daily_debit_count,daily_debit_amount,monthly_debit_amount\n")
            f.write("USR-999,Bad,bad@psp,123,IFSC,INVALID_BALANCE,2026-01-01 00:00:00,2026-01-01 00:00:00,1234567890,2026-01-01,0,0,0\n")
            temp_path = f.name

        with self.assertRaises(DataLoadError):
            load_users(temp_path)


if __name__ == "__main__":
    unittest.main()
