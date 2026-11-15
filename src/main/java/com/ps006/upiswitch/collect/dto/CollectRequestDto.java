package com.ps006.upiswitch.collect.dto;

import com.ps006.upiswitch.collect.model.CollectStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record CollectRequestDto(
        String txnId,
        String requesterVpa,
        String payerVpa,
        BigDecimal amount,
        int expiryMinutes,
        boolean pinFlag,
        LocalDateTime expiresAt,
        CollectStatus status
) {}

