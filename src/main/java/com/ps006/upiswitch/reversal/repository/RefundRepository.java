package com.ps006.upiswitch.reversal.repository;

import com.ps006.upiswitch.reversal.entity.RefundEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RefundRepository extends JpaRepository<RefundEntity, Long> {
    List<RefundEntity> findByOriginalTxnId(String originalTxnId);
}

