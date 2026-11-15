package com.ps006.upiswitch.command.loader;

import com.ps006.upiswitch.common.entity.HolidayEntity;
import com.ps006.upiswitch.common.repository.HolidayRepository;
import com.ps006.upiswitch.common.util.BusinessCalendarService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

@Component
public class HolidayLoader {

    private static final Logger log = LoggerFactory.getLogger(HolidayLoader.class);
    private static final String EXPECTED_HEADER = "date,name";

    private final HolidayRepository holidayRepository;
    private final BusinessCalendarService businessCalendarService;

    public HolidayLoader(HolidayRepository holidayRepository, BusinessCalendarService businessCalendarService) {
        this.holidayRepository = holidayRepository;
        this.businessCalendarService = businessCalendarService;
    }

    @Transactional
    public IngestionResult load(Path holidaysPath) throws IOException {
        IngestionResult result = new IngestionResult("holidays.csv");
        if (!Files.exists(holidaysPath)) {
            result.addError(0, "", "Holidays file not found at " + holidaysPath);
            return result;
        }

        Set<LocalDate> seenDates = new HashSet<>();

        try (BufferedReader reader = Files.newBufferedReader(holidaysPath, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            int lineNo = 1;

            if (headerLine == null) {
                result.addError(1, "", "File is completely empty");
                return result;
            }

            if (!headerLine.trim().equalsIgnoreCase(EXPECTED_HEADER)) {
                result.addError(1, headerLine, String.format("Invalid header. Expected '%s', found '%s'", EXPECTED_HEADER, headerLine));
                return result;
            }

            String row;
            while ((row = reader.readLine()) != null) {
                lineNo++;
                String trimmed = row.trim();
                if (trimmed.isEmpty()) continue;

                result.incrementProcessed();
                String[] parts = trimmed.split(",", -1);
                if (parts.length != 2) {
                    result.addError(lineNo, trimmed, String.format("Expected 2 columns, found %d", parts.length));
                    continue;
                }

                String dateStr = parts[0].trim();
                String name = parts[1].trim();

                if (dateStr.isEmpty() || name.isEmpty()) {
                    result.addError(lineNo, trimmed, "Required column (date or name) is blank");
                    continue;
                }

                LocalDate date;
                try {
                    date = LocalDate.parse(dateStr);
                } catch (DateTimeParseException e) {
                    result.addError(lineNo, trimmed, "Invalid date format (must be YYYY-MM-DD): " + dateStr);
                    continue;
                }

                if (seenDates.contains(date)) {
                    result.addError(lineNo, trimmed, "Duplicate holiday date detected: " + dateStr);
                    continue;
                }
                seenDates.add(date);

                holidayRepository.save(new HolidayEntity(date, name));
                businessCalendarService.addHoliday(date);
                result.incrementSuccess();
            }
        }

        log.info("Loaded {} holidays with {} errors from {}", result.getSuccessfullyLoaded(), result.getErrorCount(), holidaysPath);
        return result;
    }
}

