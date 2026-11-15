package com.ps006.upiswitch.common;

import com.ps006.upiswitch.common.util.BankingCalendarService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BankingCalendarServiceTest {

    private BankingCalendarService calendarService;

    @BeforeEach
    void setUp() {
        calendarService = new BankingCalendarService();
        calendarService.loadHolidays(Set.of(
                LocalDate.of(2026, 10, 2),  // Gandhi Jayanti (Friday)
                LocalDate.of(2026, 10, 20), // Vijaya Dashami (Tuesday)
                LocalDate.of(2026, 11, 9)   // Deepavali (Monday)
        ));
    }

    @Test
    @DisplayName("Verify weekends are excluded from working days")
    void testWeekendExclusion() {
        // 2026-10-03 is Saturday, 2026-10-04 is Sunday
        assertFalse(calendarService.isWorkingDay(LocalDate.of(2026, 10, 3)));
        assertFalse(calendarService.isWorkingDay(LocalDate.of(2026, 10, 4)));
        // 2026-10-05 is Monday
        assertTrue(calendarService.isWorkingDay(LocalDate.of(2026, 10, 5)));
    }

    @Test
    @DisplayName("Verify statutory holidays are excluded from working days")
    void testHolidayExclusion() {
        // Gandhi Jayanti Friday
        assertFalse(calendarService.isWorkingDay(LocalDate.of(2026, 10, 2)));
        // Normal Thursday before
        assertTrue(calendarService.isWorkingDay(LocalDate.of(2026, 10, 1)));
    }

    @Test
    @DisplayName("Verify 5-working-day calculation spanning weekends and holidays")
    void testWorkingDaysCalculationSpanningHolidays() {
        // DSP-008 raised on Thu Oct 15, 2026
        // Working days: Fri Oct 16 (1), Mon Oct 19 (2), Wed Oct 21 (3), Thu Oct 22 (4), Fri Oct 23 (5)
        // (Tue Oct 20 is Vijaya Dashami holiday)
        LocalDate deadline = calendarService.addWorkingDays(LocalDate.of(2026, 10, 15), 5);
        assertEquals(LocalDate.of(2026, 10, 23), deadline);
    }

    @Test
    @DisplayName("Verify 5-working-day calculation spanning Deepavali")
    void testWorkingDaysSpanningDeepavali() {
        // DSP-004 raised on Mon Nov 02, 2026
        // Working days: Tue Nov 03 (1), Wed Nov 04 (2), Thu Nov 05 (3), Fri Nov 06 (4), Tue Nov 10 (5)
        // (Mon Nov 09 is Deepavali holiday)
        LocalDate deadline = calendarService.addWorkingDays(LocalDate.of(2026, 11, 2), 5);
        assertEquals(LocalDate.of(2026, 11, 10), deadline);
    }
}

