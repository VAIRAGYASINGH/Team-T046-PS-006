package com.ps006.upiswitch.counter.model;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyCounterModel(
        Long counterId,
        String userId,
        LocalDate counterDate,
        int debitCount,
        BigDecimal debitAmount
) {}

