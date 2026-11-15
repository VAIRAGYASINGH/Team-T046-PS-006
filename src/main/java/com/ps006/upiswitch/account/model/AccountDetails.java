package com.ps006.upiswitch.account.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record AccountDetails(
        Long accountId,
        String accountNumber,
        String ifsc,
        String userId,
        String merchantId,
        BigDecimal balance,
        String currency,
        Long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}

