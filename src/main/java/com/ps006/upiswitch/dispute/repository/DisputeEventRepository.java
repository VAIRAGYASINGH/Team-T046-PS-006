package com.ps006.upiswitch.dispute.repository;

import com.ps006.upiswitch.dispute.entity.DisputeEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DisputeEventRepository extends JpaRepository<DisputeEventEntity, Long> {
    List<DisputeEventEntity> findByDisputeIdOrderByTimestampAsc(String disputeId);
    List<DisputeEventEntity> findAllByOrderByTimestampAsc();
}

