package com.ps006.upiswitch.merchant.service;

import com.ps006.upiswitch.common.exception.EntityNotFoundException;
import com.ps006.upiswitch.merchant.entity.MerchantEntity;
import com.ps006.upiswitch.merchant.mapper.MerchantMapper;
import com.ps006.upiswitch.merchant.model.MerchantProfile;
import com.ps006.upiswitch.merchant.repository.MerchantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class MerchantService {

    private final MerchantRepository merchantRepository;
    private final MerchantMapper merchantMapper;

    public MerchantService(MerchantRepository merchantRepository, MerchantMapper merchantMapper) {
        this.merchantRepository = merchantRepository;
        this.merchantMapper = merchantMapper;
    }

    @Transactional
    public MerchantProfile saveMerchant(MerchantProfile merchantProfile) {
        MerchantEntity entity = merchantMapper.toEntity(merchantProfile);
        MerchantEntity saved = merchantRepository.save(entity);
        return merchantMapper.toDomain(saved);
    }

    @Transactional
    public MerchantEntity saveEntity(MerchantEntity entity) {
        return merchantRepository.save(entity);
    }

    public Optional<MerchantProfile> findById(String merchantId) {
        return merchantRepository.findById(merchantId).map(merchantMapper::toDomain);
    }

    public MerchantProfile getMerchantOrThrow(String merchantId) {
        return findById(merchantId)
                .orElseThrow(() -> new EntityNotFoundException("Merchant", merchantId));
    }

    public boolean existsById(String merchantId) {
        return merchantRepository.existsById(merchantId);
    }

    public List<MerchantProfile> findAllMerchants() {
        return merchantRepository.findAll().stream()
                .map(merchantMapper::toDomain)
                .collect(Collectors.toList());
    }
}
