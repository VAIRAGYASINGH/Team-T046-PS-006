"""Business calendar service for RBI/NPCI statutory working-day and holiday calculations.

Excludes Saturdays, Sundays, and official statutory bank holidays loaded from holidays.csv.
"""

from datetime import date, datetime, time, timedelta
from typing import Iterable, Optional, Set, Union

from src.models import Holiday


class BusinessCalendarService:
    """Calculates working days, statutory deadlines, and holiday-aware SLA clocks."""

    def __init__(self, holidays: Optional[Iterable[Union[Holiday, date]]] = None):
        self._holidays: Set[date] = set()
        if holidays:
            self.load_holidays(holidays)

    def load_holidays(self, holidays: Iterable[Union[Holiday, date]]) -> None:
        """Load or replace bank holiday dates."""
        self._holidays.clear()
        for item in holidays:
            if isinstance(item, Holiday):
                self._holidays.add(item.date)
            elif isinstance(item, date):
                self._holidays.add(item)

    def add_holiday(self, holiday_date: date) -> None:
        """Register a single holiday date."""
        self._holidays.add(holiday_date)

    @property
    def holidays(self) -> Set[date]:
        """Set of registered statutory bank holidays."""
        return set(self._holidays)

    def is_working_day(self, check_date: date) -> bool:
        """Determine if a date is a working day (skips Saturday, Sunday, and registered holidays)."""
        if check_date is None:
            return False
        # Python weekday: Monday is 0, Saturday is 5, Sunday is 6
        if check_date.weekday() >= 5:
            return False
        return check_date not in self._holidays

    def add_working_days(self, start_date: date, working_days_to_add: int) -> date:
        """Add n working days to a start date, skipping weekends and statutory holidays.

        Counting begins strictly from the calendar day following start_date (Day 1 is start_date + 1).
        """
        if start_date is None or working_days_to_add <= 0:
            return start_date

        current = start_date
        added = 0
        while added < working_days_to_add:
            current += timedelta(days=1)
            if self.is_working_day(current):
                added += 1
        return current

    def calculate_representment_deadline(self, dispute_date: date) -> datetime:
        """Calculate the 5-working-day representment deadline ending at 23:59:59."""
        if dispute_date is None:
            raise ValueError("dispute_date cannot be None")
        fifth_working_day = self.add_working_days(dispute_date, 5)
        return datetime.combine(fifth_working_day, time(23, 59, 59))

    def calculate_pre_arbitration_deadline(self, representment_date: date) -> datetime:
        """Calculate the pre-arbitration deadline: exactly 7 calendar days from representment date at 23:59:59.

        Day 7 is inclusive.
        """
        if representment_date is None:
            raise ValueError("representment_date cannot be None")
        seventh_day = representment_date + timedelta(days=7)
        return datetime.combine(seventh_day, time(23, 59, 59))

    def is_past_deadline(self, timestamp: datetime, deadline: Optional[datetime]) -> bool:
        """Check if a timestamp is strictly after the deadline."""
        if timestamp is None or deadline is None:
            return False
        return timestamp > deadline
