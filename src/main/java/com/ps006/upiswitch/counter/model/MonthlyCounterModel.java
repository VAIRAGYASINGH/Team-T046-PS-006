package com.ps006.upiswitch.counter.model;

import java.math.BigDecimal;

public record MonthlyCounterModel(
        Long monthlyCounterId,
        String userId,
        String yearMonth,
        int debitCount,
        BigDecimal debitAmount
) {}

