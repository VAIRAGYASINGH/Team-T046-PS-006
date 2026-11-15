package com.ps006.upiswitch.collect.mapper;

import com.ps006.upiswitch.collect.dto.CollectRequestDto;
import com.ps006.upiswitch.collect.entity.CollectRequestEntity;
import com.ps006.upiswitch.collect.model.CollectRequest;
import org.springframework.stereotype.Component;

@Component
public class CollectMapper {

    public CollectRequestDto toDto(CollectRequestEntity entity) {
        if (entity == null) return null;
        return new CollectRequestDto(
                entity.getTxnId(),
                entity.getRequesterVpa(),
                entity.getPayerVpa(),
                entity.getAmount(),
                entity.getExpiryMinutes(),
                entity.isPinFlag(),
                entity.getExpiresAt(),
                entity.getStatus()
        );
    }

    public CollectRequest toDomain(CollectRequestEntity entity) {
        if (entity == null) return null;
        return new CollectRequest(
                entity.getRequestId(),
                entity.getPayerVpa(),
                entity.getPayeeVpa(),
                entity.getAmount(),
                entity.getCreationTimestamp(),
                entity.getExpiryTimestamp(),
                entity.isPinRequired(),
                entity.getStatus(),
                entity.getApprovalTimestamp(),
                entity.getDeclineTimestamp()
        );
    }
}

