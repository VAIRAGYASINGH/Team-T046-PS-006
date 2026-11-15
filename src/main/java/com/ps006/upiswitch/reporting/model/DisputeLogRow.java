package com.ps006.upiswitch.reporting.model;

import java.time.LocalDateTime;

public record DisputeLogRow(
        int lineNo,
        String disputeId,
        String txnId,
        String type,
        String action,
        LocalDateTime timestamp,
        String resultState,
        LocalDateTime deadline,
        String ruleIds
) {}

