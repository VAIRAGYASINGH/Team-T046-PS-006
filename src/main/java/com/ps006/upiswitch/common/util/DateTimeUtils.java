package com.ps006.upiswitch.common.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Standard date-time formatting and parsing utilities adhering to dataset specifications.
 * Dates: YYYY-MM-DD
 * Timestamps: YYYY-MM-DD HH:MM:SS (24-hour clock, IST, no timezone suffix)
 */
public final class DateTimeUtils {

    public static final String DATE_PATTERN = "yyyy-MM-dd";
    public static final String TIMESTAMP_PATTERN = "yyyy-MM-dd HH:mm:ss";

    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern(DATE_PATTERN);
    public static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern(TIMESTAMP_PATTERN);

    private DateTimeUtils() {}

    public static LocalDate parseDate(String text) {
        if (text == null || text.isBlank()) return null;
        return LocalDate.parse(text.trim(), DATE_FORMATTER);
    }

    public static LocalDateTime parseTimestamp(String text) {
        if (text == null || text.isBlank()) return null;
        return LocalDateTime.parse(text.trim(), TIMESTAMP_FORMATTER);
    }

    public static LocalDateTime parse(String text) {
        return parseTimestamp(text);
    }

    public static String formatDate(LocalDate date) {
        if (date == null) return "";
        return date.format(DATE_FORMATTER);
    }

    public static String formatTimestamp(LocalDateTime dateTime) {
        if (dateTime == null) return "";
        return dateTime.format(TIMESTAMP_FORMATTER);
    }

    public static String format(LocalDateTime dateTime) {
        return formatTimestamp(dateTime);
    }
}
