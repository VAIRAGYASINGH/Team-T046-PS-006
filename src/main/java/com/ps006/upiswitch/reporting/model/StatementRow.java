package com.ps006.upiswitch.reporting.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record StatementRow(
        String vpa,
        LocalDateTime timestamp,
        String txnId,
        String legType,
        BigDecimal debit,
        BigDecimal credit,
        BigDecimal runningBalance
) {}

