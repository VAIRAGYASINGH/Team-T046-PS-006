package com.ps006.upiswitch.ledger.service;

import com.ps006.upiswitch.ledger.entity.LedgerEntryEntity;
import com.ps006.upiswitch.ledger.model.LegType;
import com.ps006.upiswitch.ledger.repository.LedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Transactional(readOnly = true)
public class LedgerService {

    private final LedgerRepository ledgerRepository;
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    public LedgerService(LedgerRepository ledgerRepository) {
        this.ledgerRepository = ledgerRepository;
        // Initialize sequence counter from existing database entries
        Long maxSeq = ledgerRepository.findTopByOrderBySequenceNumberDesc()
                .map(LedgerEntryEntity::getSequenceNumber)
                .orElse(0L);
        sequenceCounter.set(maxSeq);
    }

    private synchronized Long getNextSequence() {
        return sequenceCounter.incrementAndGet();
    }

    @Transactional
    public LedgerEntryEntity recordDebit(String txnId, Long accountId, String vpa, BigDecimal amount,
                                        BigDecimal balanceAfter, LocalDateTime timestamp, String description) {
        BigDecimal stdAmount = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal stdBalAfter = balanceAfter.setScale(2, RoundingMode.HALF_UP);
        LedgerEntryEntity entry = new LedgerEntryEntity(
                getNextSequence(),
                txnId,
                accountId,
                vpa,
                LegType.DEBIT,
                stdAmount,
                null,
                stdBalAfter,
                timestamp,
                null,
                description
        );
        return ledgerRepository.save(entry);
    }

    @Transactional
    public LedgerEntryEntity recordCredit(String txnId, Long accountId, String vpa, BigDecimal amount,
                                         BigDecimal balanceAfter, LocalDateTime timestamp, String description) {
        BigDecimal stdAmount = amount.setScale(2, RoundingMode.HALF_UP);
        BigDecimal stdBalAfter = balanceAfter.setScale(2, RoundingMode.HALF_UP);
        LedgerEntryEntity entry = new LedgerEntryEntity(
                getNextSequence(),
                txnId,
                accountId,
                vpa,
                LegType.CREDIT,
                null,
                stdAmount,
                stdBalAfter,
                timestamp,
                null,
                description
        );
        return ledgerRepository.save(entry);
    }

    @Transactional
    public LedgerEntryEntity recordEntry(String txnId, Long accountId, String vpa, LegType legType,
                                        BigDecimal debitAmount, BigDecimal creditAmount,
                                        BigDecimal balanceAfter, LocalDateTime timestamp,
                                        Long referenceEntryId, String description) {
        BigDecimal stdDebit = debitAmount != null ? debitAmount.setScale(2, RoundingMode.HALF_UP) : null;
        BigDecimal stdCredit = creditAmount != null ? creditAmount.setScale(2, RoundingMode.HALF_UP) : null;
        BigDecimal stdBalAfter = balanceAfter.setScale(2, RoundingMode.HALF_UP);

        if (referenceEntryId == null) {
            // Find parent entry for this transaction
            referenceEntryId = ledgerRepository.findByTxnIdOrderBySequenceNumberAsc(txnId).stream()
                    .filter(e -> e.getAccountId().equals(accountId))
                    .map(LedgerEntryEntity::getLedgerEntryId)
                    .findFirst()
                    .orElse(null);
        }

        LedgerEntryEntity entry = new LedgerEntryEntity(
                getNextSequence(),
                txnId,
                accountId,
                vpa,
                legType,
                stdDebit,
                stdCredit,
                stdBalAfter,
                timestamp,
                referenceEntryId,
                description
        );
        return ledgerRepository.save(entry);
    }

    @Transactional
    public LedgerEntryEntity recordReversal(String txnId, Long accountId, String vpa, BigDecimal amount,
                                           BigDecimal balanceAfter, LocalDateTime timestamp, String description) {
        return recordEntry(txnId, accountId, vpa, LegType.REVERSAL, null, amount, balanceAfter, timestamp, null, description);
    }

    public List<LedgerEntryEntity> getEntriesForAccount(Long accountId) {
        return ledgerRepository.findStatementsForAccount(accountId);
    }

    public List<LedgerEntryEntity> getEntriesForTxn(String txnId) {
        return ledgerRepository.findByTxnIdOrderBySequenceNumberAsc(txnId);
    }

    public BigDecimal getTotalDebits() {
        BigDecimal val = ledgerRepository.sumTotalDebits();
        return val != null ? val.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2);
    }

    public BigDecimal getTotalCredits() {
        BigDecimal val = ledgerRepository.sumTotalCredits();
        return val != null ? val.setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO.setScale(2);
    }

    public boolean verifyDoubleEntryInvariant() {
        BigDecimal totalDebits = getTotalDebits();
        BigDecimal totalCredits = getTotalCredits();
        return totalDebits.compareTo(totalCredits) == 0;
    }
}

