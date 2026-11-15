package com.ps006.upiswitch.common.repository;

import com.ps006.upiswitch.common.entity.ReconciliationRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReconciliationRecordRepository extends JpaRepository<ReconciliationRecordEntity, Long> {
    List<ReconciliationRecordEntity> findByTxnId(String txnId);
}

