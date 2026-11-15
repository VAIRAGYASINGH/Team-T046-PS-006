package com.ps006.upiswitch.statement.dto;

import com.ps006.upiswitch.ledger.model.LegType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record StatementRecordDto(
        String vpa,
        LocalDateTime timestamp,
        String txnId,
        LegType legType,
        BigDecimal debit,
        BigDecimal credit,
        BigDecimal runningBalance
) {}

