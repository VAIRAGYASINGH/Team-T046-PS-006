package com.ps006.upiswitch.collect.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Domain model representing a UPI collect request.
 * Stores:
 * - Request ID
 * - Payer VPA
 * - Payee VPA
 * - Amount
 * - Creation timestamp
 * - Expiry timestamp
 * - PIN requirement
 * - Status
 * - Approval timestamp
 * - Decline timestamp
 */
public record CollectRequest(
        String requestId,
        String payerVpa,
        String payeeVpa,
        BigDecimal amount,
        LocalDateTime creationTimestamp,
        LocalDateTime expiryTimestamp,
        boolean pinRequired,
        CollectStatus status,
        LocalDateTime approvalTimestamp,
        LocalDateTime declineTimestamp
) {}

