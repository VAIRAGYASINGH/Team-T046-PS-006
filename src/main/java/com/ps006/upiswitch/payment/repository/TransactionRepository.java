package com.ps006.upiswitch.payment.repository;

import com.ps006.upiswitch.payment.entity.TransactionEntity;
import com.ps006.upiswitch.payment.model.TransactionStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<TransactionEntity, String> {

    Optional<TransactionEntity> findByTxnId(String txnId);

    Optional<TransactionEntity> findByRrn(String rrn);

    List<TransactionEntity> findByPayerVpaOrderByInitiatedAtAsc(String payerVpa);

    List<TransactionEntity> findByPayeeVpaOrderByInitiatedAtAsc(String payeeVpa);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TransactionEntity t WHERE t.txnId = :txnId")
    Optional<TransactionEntity> findByTxnIdForUpdate(@Param("txnId") String txnId);

    @Query("SELECT t FROM TransactionEntity t WHERE t.status = :status AND t.initiatedAt <= :cutoffTime")
    List<TransactionEntity> findByStatusAndInitiatedAtBefore(
            @Param("status") TransactionStatus status,
            @Param("cutoffTime") LocalDateTime cutoffTime
    );
}

