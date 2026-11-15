package com.ps006.upiswitch.vpa.model;

import java.time.LocalDateTime;

public record VpaDetails(
        String vpa,
        Long accountId,
        OwnerType ownerType,
        String ownerId,
        LocalDateTime createdAt,
        boolean active
) {}

