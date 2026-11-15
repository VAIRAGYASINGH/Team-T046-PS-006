package com.ps006.upiswitch.reporting.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TxnLogRow(
        int lineNo,
        String txnId,
        String command,
        LocalDateTime timestamp,
        String payerVpa,
        String payeeVpa,
        BigDecimal amount,
        String status,
        String rrn,
        String ruleIds
) {}

