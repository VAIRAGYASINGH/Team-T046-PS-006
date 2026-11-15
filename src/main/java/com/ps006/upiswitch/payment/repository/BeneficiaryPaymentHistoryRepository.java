package com.ps006.upiswitch.payment.repository;

import com.ps006.upiswitch.payment.entity.BeneficiaryPaymentHistoryEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BeneficiaryPaymentHistoryRepository extends JpaRepository<BeneficiaryPaymentHistoryEntity, Long> {

    Optional<BeneficiaryPaymentHistoryEntity> findByPayerVpaAndPayeeVpa(String payerVpa, String payeeVpa);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM BeneficiaryPaymentHistoryEntity b WHERE b.payerVpa = :payerVpa AND b.payeeVpa = :payeeVpa")
    Optional<BeneficiaryPaymentHistoryEntity> findByPayerVpaAndPayeeVpaForUpdate(
            @Param("payerVpa") String payerVpa,
            @Param("payeeVpa") String payeeVpa
    );
}

