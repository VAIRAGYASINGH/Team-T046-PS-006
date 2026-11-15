package com.ps006.upiswitch.common.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized business calendar service adhering to RBI/NPCI statutory holiday
 * and working-day rules. Excludes Saturdays, Sundays, and dates loaded from holidays.csv.
 * Injects java.time.Clock for deterministic time evaluation in testing.
 */
@Service
public class BusinessCalendarService {

    private final Clock clock;
    private final Set<LocalDate> holidays = ConcurrentHashMap.newKeySet();

    @Autowired
    public BusinessCalendarService(Clock clock) {
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
    }

    public BusinessCalendarService() {
        this(Clock.systemDefaultZone());
    }

    public Clock getClock() {
        return clock;
    }

    public void loadHolidays(Set<LocalDate> holidayDates) {
        if (holidayDates != null) {
            holidays.clear();
            holidays.addAll(holidayDates);
        }
    }

    public void addHoliday(LocalDate date) {
        if (date != null) {
            holidays.add(date);
        }
    }

    public Set<LocalDate> getHolidays() {
        return Collections.unmodifiableSet(holidays);
    }

    public boolean isWorkingDay(LocalDate date) {
        if (date == null) return false;
        DayOfWeek dow = date.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        return !holidays.contains(date);
    }

    /**
     * Adds n working days to a start date, skipping weekends and statutory holidays.
     * Counting begins from the day after startDate.
     */
    public LocalDate addWorkingDays(LocalDate startDate, int workingDaysToAdd) {
        if (startDate == null || workingDaysToAdd <= 0) return startDate;

        LocalDate current = startDate;
        int added = 0;
        while (added < workingDaysToAdd) {
            current = current.plusDays(1);
            if (isWorkingDay(current)) {
                added++;
            }
        }
        return current;
    }

    /**
     * Calculates the representment deadline: exactly 5 working days from the dispute date,
     * ending at 23:59:59.
     */
    public LocalDateTime calculateRepresentmentDeadline(LocalDate disputeDate) {
        if (disputeDate == null) return null;
        return addWorkingDays(disputeDate, 5).atTime(23, 59, 59);
    }

    /**
     * Calculates the pre-arbitration deadline: exactly 7 calendar days from the representment date,
     * ending at 23:59:59. Day 7 is inclusive.
     */
    public LocalDateTime calculatePreArbitrationDeadline(LocalDate representmentDate) {
        if (representmentDate == null) return null;
        return representmentDate.plusDays(7).atTime(23, 59, 59);
    }

    /**
     * Evaluates if a given timestamp has exceeded the deadline.
     */
    public boolean isPastDeadline(LocalDateTime timestamp, LocalDateTime deadline) {
        if (timestamp == null || deadline == null) return false;
        return timestamp.isAfter(deadline);
    }
}

