package com.ps006.upiswitch.merchant.repository;

import com.ps006.upiswitch.merchant.entity.MerchantEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MerchantRepository extends JpaRepository<MerchantEntity, String> {
    Optional<MerchantEntity> findByMerchantId(String merchantId);
}

