package com.ps006.upiswitch.collect.repository;

import com.ps006.upiswitch.collect.entity.CollectRequestEntity;
import com.ps006.upiswitch.collect.model.CollectStatus;
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
public interface CollectRepository extends JpaRepository<CollectRequestEntity, String> {

    Optional<CollectRequestEntity> findByTxnId(String txnId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM CollectRequestEntity c WHERE c.txnId = :txnId")
    Optional<CollectRequestEntity> findByTxnIdForUpdate(@Param("txnId") String txnId);

    List<CollectRequestEntity> findByStatus(CollectStatus status);

    @Query("SELECT c FROM CollectRequestEntity c WHERE c.status = :status AND c.expiresAt <= :cutoffTime")
    List<CollectRequestEntity> findByStatusAndExpiresAtBefore(
            @Param("status") CollectStatus status,
            @Param("cutoffTime") LocalDateTime cutoffTime
    );
}

