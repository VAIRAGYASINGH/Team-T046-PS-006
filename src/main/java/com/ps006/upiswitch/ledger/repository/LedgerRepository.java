package com.ps006.upiswitch.ledger.repository;

import com.ps006.upiswitch.ledger.entity.LedgerEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LedgerRepository extends JpaRepository<LedgerEntryEntity, Long> {

    Optional<LedgerEntryEntity> findTopByOrderBySequenceNumberDesc();

    List<LedgerEntryEntity> findByTxnIdOrderBySequenceNumberAsc(String txnId);

    List<LedgerEntryEntity> findByAccountIdOrderBySequenceNumberAsc(Long accountId);

    @Query("SELECT l FROM LedgerEntryEntity l WHERE l.accountId = :accountId ORDER BY l.entryTimestamp ASC, l.sequenceNumber ASC")
    List<LedgerEntryEntity> findStatementsForAccount(@Param("accountId") Long accountId);

    @Query("SELECT l FROM LedgerEntryEntity l WHERE l.accountId = :accountId AND l.entryTimestamp >= :startTs AND l.entryTimestamp <= :endTs ORDER BY l.entryTimestamp ASC, l.sequenceNumber ASC")
    List<LedgerEntryEntity> findStatementsForAccountAndTimestampBetween(
            @Param("accountId") Long accountId,
            @Param("startTs") java.time.LocalDateTime startTs,
            @Param("endTs") java.time.LocalDateTime endTs
    );

    @Query("SELECT COALESCE(SUM(l.debitAmount), 0.00) FROM LedgerEntryEntity l")
    java.math.BigDecimal sumTotalDebits();

    @Query("SELECT COALESCE(SUM(l.creditAmount), 0.00) FROM LedgerEntryEntity l")
    java.math.BigDecimal sumTotalCredits();

    Optional<LedgerEntryEntity> findTopByAccountIdOrderBySequenceNumberDesc(Long accountId);
}

