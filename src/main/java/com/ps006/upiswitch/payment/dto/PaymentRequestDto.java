package com.ps006.upiswitch.payment.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentRequestDto(
        String command,
        String txnId,
        String payerVpa,
        String payeeVpa,
        BigDecimal amount,
        LocalDateTime timestamp,
        String remarks
) {}

