package com.ps006.upiswitch.user.service;

import com.ps006.upiswitch.common.exception.EntityNotFoundException;
import com.ps006.upiswitch.user.entity.UserEntity;
import com.ps006.upiswitch.user.mapper.UserMapper;
import com.ps006.upiswitch.user.model.UserProfile;
import com.ps006.upiswitch.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    public UserService(UserRepository userRepository, UserMapper userMapper) {
        this.userRepository = userRepository;
        this.userMapper = userMapper;
    }

    @Transactional
    public UserProfile saveUser(UserProfile userProfile) {
        UserEntity entity = userMapper.toEntity(userProfile);
        UserEntity saved = userRepository.save(entity);
        return userMapper.toDomain(saved);
    }

    @Transactional
    public UserEntity saveEntity(UserEntity entity) {
        return userRepository.save(entity);
    }

    public Optional<UserProfile> findById(String userId) {
        return userRepository.findById(userId).map(userMapper::toDomain);
    }

    public UserProfile getUserOrThrow(String userId) {
        return findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User", userId));
    }

    public boolean existsById(String userId) {
        return userRepository.existsById(userId);
    }

    public List<UserProfile> findAllUsers() {
        return userRepository.findAll().stream()
                .map(userMapper::toDomain)
                .collect(Collectors.toList());
    }
}
