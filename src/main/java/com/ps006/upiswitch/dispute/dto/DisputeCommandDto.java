package com.ps006.upiswitch.dispute.dto;

import com.ps006.upiswitch.dispute.model.DisputeAction;
import com.ps006.upiswitch.dispute.model.DisputeType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DisputeCommandDto(
        String disputeId,
        String txnId,
        DisputeType type,
        DisputeAction action,
        String raisedBy,
        BigDecimal amount,
        LocalDateTime timestamp,
        String remarks
) {}

