package com.ps006.upiswitch.vpa.service;

import com.ps006.upiswitch.common.exception.EntityNotFoundException;
import com.ps006.upiswitch.vpa.entity.VpaEntity;
import com.ps006.upiswitch.vpa.mapper.VpaMapper;
import com.ps006.upiswitch.vpa.model.OwnerType;
import com.ps006.upiswitch.vpa.model.VpaDetails;
import com.ps006.upiswitch.vpa.repository.VpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class VpaService {

    private final VpaRepository vpaRepository;
    private final VpaMapper vpaMapper;

    public VpaService(VpaRepository vpaRepository, VpaMapper vpaMapper) {
        this.vpaRepository = vpaRepository;
        this.vpaMapper = vpaMapper;
    }

    @Transactional
    public VpaDetails saveVpa(VpaDetails details) {
        VpaEntity entity = vpaMapper.toEntity(details);
        VpaEntity saved = vpaRepository.save(entity);
        return vpaMapper.toDomain(saved);
    }

    public Optional<VpaDetails> findByVpa(String vpa) {
        return vpaRepository.findByVpa(vpa).map(vpaMapper::toDomain);
    }

    public VpaDetails getVpaOrThrow(String vpa) {
        return findByVpa(vpa)
                .orElseThrow(() -> new EntityNotFoundException("VPA", vpa));
    }

    public Optional<VpaDetails> findByAccountId(Long accountId) {
        return vpaRepository.findByAccountId(accountId).map(vpaMapper::toDomain);
    }

    public boolean isMerchantVpa(String vpa) {
        return findByVpa(vpa)
                .map(v -> v.ownerType() == OwnerType.MERCHANT)
                .orElse(false);
    }

    public boolean isUserVpa(String vpa) {
        return findByVpa(vpa)
                .map(v -> v.ownerType() == OwnerType.USER)
                .orElse(false);
    }

    public Long getAccountIdForVpa(String vpa) {
        return getVpaOrThrow(vpa).accountId();
    }

    public List<VpaDetails> findAll() {
        return vpaRepository.findAll().stream()
                .map(vpaMapper::toDomain)
                .collect(Collectors.toList());
    }
}

