package com.ps006.upiswitch.user.model;

import java.time.LocalDateTime;

public record UserProfile(
        String userId,
        String name,
        String mobile,
        LocalDateTime deviceBoundAt,
        LocalDateTime createdAt
) {}

