package com.ps006.upiswitch.vpa.repository;

import com.ps006.upiswitch.vpa.entity.VpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VpaRepository extends JpaRepository<VpaEntity, String> {
    Optional<VpaEntity> findByVpa(String vpa);
    List<VpaEntity> findByOwnerId(String ownerId);
    Optional<VpaEntity> findByAccountId(Long accountId);
}

