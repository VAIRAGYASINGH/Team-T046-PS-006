package com.ps006.upiswitch.vpa.mapper;

import com.ps006.upiswitch.vpa.entity.VpaEntity;
import com.ps006.upiswitch.vpa.model.VpaDetails;
import org.springframework.stereotype.Component;

@Component
public class VpaMapper {

    public VpaDetails toDomain(VpaEntity entity) {
        if (entity == null) return null;
        return new VpaDetails(
                entity.getVpa(),
                entity.getAccountId(),
                entity.getOwnerType(),
                entity.getOwnerId(),
                entity.getCreatedAt(),
                entity.isActive()
        );
    }

    public VpaEntity toEntity(VpaDetails details) {
        if (details == null) return null;
        return new VpaEntity(
                details.vpa(),
                details.accountId(),
                details.ownerType(),
                details.ownerId(),
                details.createdAt(),
                details.active()
        );
    }
}

