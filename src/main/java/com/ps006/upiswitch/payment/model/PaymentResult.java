package com.ps006.upiswitch.payment.model;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

public record PaymentResult(
        String txnId,
        String rrn,
        TransactionStatus status,
        List<String> ruleIds,
        String errorCode,
        LocalDateTime timestamp
) {
    public static PaymentResult success(String txnId, String rrn, LocalDateTime timestamp) {
        return new PaymentResult(txnId, rrn, TransactionStatus.SUCCESS, Collections.emptyList(), null, timestamp);
    }

    public static PaymentResult pending(String txnId, String rrn, LocalDateTime timestamp) {
        return new PaymentResult(txnId, rrn, TransactionStatus.PENDING, Collections.emptyList(), null, timestamp);
    }

    public static PaymentResult failure(String txnId, String rrn, List<String> ruleIds, String errorCode, LocalDateTime timestamp) {
        return new PaymentResult(txnId, rrn, TransactionStatus.FAILED, ruleIds != null ? ruleIds : Collections.emptyList(), errorCode, timestamp);
    }

    public String getPrimaryRuleId() {
        return (ruleIds != null && !ruleIds.isEmpty()) ? ruleIds.get(0) : "";
    }

    public String getRuleIdsFormatted() {
        return (ruleIds != null && !ruleIds.isEmpty()) ? String.join(";", ruleIds) : "";
    }
}

