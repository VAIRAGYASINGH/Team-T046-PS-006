package com.ps006.upiswitch.collect.service;

import com.ps006.upiswitch.collect.entity.CollectRequestEntity;
import com.ps006.upiswitch.collect.model.CollectStatus;
import com.ps006.upiswitch.collect.repository.CollectRepository;
import com.ps006.upiswitch.payment.entity.TransactionEntity;
import com.ps006.upiswitch.payment.entity.TransactionEventEntity;
import com.ps006.upiswitch.payment.model.TransactionStatus;
import com.ps006.upiswitch.payment.repository.TransactionEventRepository;
import com.ps006.upiswitch.payment.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Transactional
public class CollectExpiryService {

    private static final Logger log = LoggerFactory.getLogger(CollectExpiryService.class);

    public static final int DEFAULT_EXPIRY_MINUTES = 30;
    public static final int MAX_EXPIRY_MINUTES = 4320; // 72 hours

    private final CollectRepository collectRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionEventRepository transactionEventRepository;

    public CollectExpiryService(CollectRepository collectRepository,
                                TransactionRepository transactionRepository,
                                TransactionEventRepository transactionEventRepository) {
        this.collectRepository = collectRepository;
        this.transactionRepository = transactionRepository;
        this.transactionEventRepository = transactionEventRepository;
    }

    /**
     * Resolves expiry minutes. Rule 1: Default expiry is 30 minutes.
     */
    public int resolveExpiryMinutes(int inputExpiryMinutes) {
        if (inputExpiryMinutes <= 0) {
            return DEFAULT_EXPIRY_MINUTES;
        }
        return inputExpiryMinutes;
    }

    /**
     * Rule 2: Maximum expiry is 72 hours (4,320 minutes).
     */
    public boolean isExpiryValid(int expiryMinutes) {
        return expiryMinutes > 0 && expiryMinutes <= MAX_EXPIRY_MINUTES;
    }

    /**
     * Rule 5 & 11: Checks if collect request has strictly expired.
     * At exact expiry (timestamp == expiresAt), request is NOT expired (isAfter is false).
     * Strictly after expiry (timestamp > expiresAt), request is expired.
     */
    public boolean isExpired(CollectRequestEntity collect, LocalDateTime currentTimestamp) {
        if (collect == null || collect.getExpiresAt() == null) {
            return false;
        }
        return currentTimestamp.isAfter(collect.getExpiresAt());
    }

    /**
     * Marks collect request and associated transaction as EXPIRED.
     */
    public void markExpired(CollectRequestEntity collect, TransactionEntity txn, LocalDateTime timestamp) {
        collect.setStatus(CollectStatus.EXPIRED);
        collectRepository.save(collect);

        if (txn != null) {
            txn.setStatus(TransactionStatus.EXPIRED);
            transactionRepository.save(txn);
        }

        transactionEventRepository.save(new TransactionEventEntity(
                collect.getTxnId(), "COLLECT_EXPIRED", timestamp, "PENDING", "EXPIRED",
                "Collect request expired; approval attempted after deadline"
        ));
    }

    /**
     * Scans and marks all pending collect requests whose expiresAt <= cutoffTime as EXPIRED.
     */
    public int processExpiredCollects(LocalDateTime cutoffTime) {
        List<CollectRequestEntity> expiredCandidates = collectRepository
                .findByStatusAndExpiresAtBefore(CollectStatus.PENDING, cutoffTime);

        int count = 0;
        for (CollectRequestEntity candidate : expiredCandidates) {
            TransactionEntity txn = transactionRepository.findByTxnId(candidate.getTxnId()).orElse(null);
            markExpired(candidate, txn, cutoffTime);
            count++;
        }
        return count;
    }
}

