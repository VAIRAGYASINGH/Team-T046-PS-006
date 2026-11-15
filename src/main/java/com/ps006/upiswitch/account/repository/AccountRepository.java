package com.ps006.upiswitch.account.repository;

import com.ps006.upiswitch.account.entity.AccountEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<AccountEntity, Long> {

    Optional<AccountEntity> findByAccountNumber(String accountNumber);

    Optional<AccountEntity> findByUserId(String userId);

    Optional<AccountEntity> findByMerchantId(String merchantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM AccountEntity a WHERE a.accountId = :accountId")
    Optional<AccountEntity> findByIdForUpdate(@Param("accountId") Long accountId);
}

