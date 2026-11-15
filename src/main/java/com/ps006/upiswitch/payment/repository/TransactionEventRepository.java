package com.ps006.upiswitch.payment.repository;

import com.ps006.upiswitch.payment.entity.TransactionEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionEventRepository extends JpaRepository<TransactionEventEntity, Long> {
    List<TransactionEventEntity> findByTxnIdOrderByTimestampAsc(String txnId);
}

