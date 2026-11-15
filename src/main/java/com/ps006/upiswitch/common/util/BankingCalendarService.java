package com.ps006.upiswitch.common.util;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;

/**
 * Retained for backward-compatibility; extends the centralized BusinessCalendarService.
 */
@Service
public class BankingCalendarService extends BusinessCalendarService {

    @Autowired
    public BankingCalendarService(Clock clock) {
        super(clock);
    }

    public BankingCalendarService() {
        super();
    }
}
