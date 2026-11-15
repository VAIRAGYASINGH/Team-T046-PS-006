package com.ps006.upiswitch.user.mapper;

import com.ps006.upiswitch.user.entity.UserEntity;
import com.ps006.upiswitch.user.model.UserProfile;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public UserProfile toDomain(UserEntity entity) {
        if (entity == null) return null;
        return new UserProfile(
                entity.getUserId(),
                entity.getName(),
                entity.getMobile(),
                entity.getDeviceBoundAt(),
                entity.getCreatedAt()
        );
    }

    public UserEntity toEntity(UserProfile profile) {
        if (profile == null) return null;
        return new UserEntity(
                profile.userId(),
                profile.name(),
                profile.mobile(),
                profile.deviceBoundAt(),
                profile.createdAt()
        );
    }
}

