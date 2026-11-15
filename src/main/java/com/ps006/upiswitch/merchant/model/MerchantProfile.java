package com.ps006.upiswitch.merchant.model;

import java.time.LocalDateTime;

public record MerchantProfile(
        String merchantId,
        String name,
        String mcc,
        boolean verified,
        LocalDateTime createdAt
) {}

