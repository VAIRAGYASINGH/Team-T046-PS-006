package com.ps006.upiswitch.dispute.repository;

import com.ps006.upiswitch.dispute.entity.DisputeEntity;
import com.ps006.upiswitch.dispute.model.DisputeState;
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
public interface DisputeRepository extends JpaRepository<DisputeEntity, String> {

    Optional<DisputeEntity> findByDisputeId(String disputeId);

    List<DisputeEntity> findByTxnId(String txnId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DisputeEntity d WHERE d.disputeId = :disputeId")
    Optional<DisputeEntity> findByDisputeIdForUpdate(@Param("disputeId") String disputeId);

    @Query("SELECT d FROM DisputeEntity d WHERE d.status IN ('OPEN_TCC', 'OPEN_CHARGEBACK') AND d.deadline <= :cutoffTime")
    List<DisputeEntity> findPendingExpiredDisputes(@Param("cutoffTime") LocalDateTime cutoffTime);

    @Query("SELECT d FROM DisputeEntity d WHERE d.status = :status AND d.deadline <= :cutoffTime")
    List<DisputeEntity> findByStatusAndDeadlineBefore(
            @Param("status") DisputeState status,
            @Param("cutoffTime") LocalDateTime cutoffTime
    );
}
