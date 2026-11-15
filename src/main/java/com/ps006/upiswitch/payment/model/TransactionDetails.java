package com.ps006.upiswitch.payment.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionDetails(
        String txnId,
        String rrn,
        String command,
        String payerVpa,
        String payeeVpa,
        BigDecimal amount,
        String remarks,
        TransactionType transactionType,
        TransactionStatus status,
        String ruleIds,
        BigDecimal refundedAmount,
        LocalDateTime initiatedAt,
        LocalDateTime reversedAt,
        LocalDateTime deemedSuccessAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}

