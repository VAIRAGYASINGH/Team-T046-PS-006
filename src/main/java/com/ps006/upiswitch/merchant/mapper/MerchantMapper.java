package com.ps006.upiswitch.merchant.mapper;

import com.ps006.upiswitch.merchant.entity.MerchantEntity;
import com.ps006.upiswitch.merchant.model.MerchantProfile;
import org.springframework.stereotype.Component;

@Component
public class MerchantMapper {

    public MerchantProfile toDomain(MerchantEntity entity) {
        if (entity == null) return null;
        return new MerchantProfile(
                entity.getMerchantId(),
                entity.getName(),
                entity.getMcc(),
                entity.isVerified(),
                entity.getCreatedAt()
        );
    }

    public MerchantEntity toEntity(MerchantProfile profile) {
        if (profile == null) return null;
        return new MerchantEntity(
                profile.merchantId(),
                profile.name(),
                profile.mcc(),
                profile.verified(),
                profile.createdAt()
        );
    }
}

