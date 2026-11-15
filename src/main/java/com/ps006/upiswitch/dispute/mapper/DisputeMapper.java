package com.ps006.upiswitch.dispute.mapper;

import com.ps006.upiswitch.dispute.dto.DisputeCommandDto;
import com.ps006.upiswitch.dispute.entity.DisputeEntity;
import org.springframework.stereotype.Component;

@Component
public class DisputeMapper {

    public DisputeCommandDto toDto(DisputeEntity entity) {
        if (entity == null) return null;
        return new DisputeCommandDto(
                entity.getDisputeId(),
                entity.getTxnId(),
                entity.getType(),
                null,
                entity.getRaisedBy(),
                entity.getAmount(),
                entity.getLodgedAt(),
                entity.getRuleIds()
        );
    }
}
