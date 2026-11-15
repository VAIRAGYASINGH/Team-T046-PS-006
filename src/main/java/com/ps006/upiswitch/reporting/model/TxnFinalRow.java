package com.ps006.upiswitch.reporting.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TxnFinalRow(
        String txnId,
        String finalStatus,
        BigDecimal amount,
        BigDecimal refundedAmount,
        LocalDateTime reversedAt,
        LocalDateTime deemedSuccessAt
) {}

