package com.ps006.upiswitch.collect.service;

import com.ps006.upiswitch.collect.entity.CollectRequestEntity;
import com.ps006.upiswitch.collect.model.CollectStatus;
import com.ps006.upiswitch.collect.repository.CollectRepository;
import com.ps006.upiswitch.common.util.RrnGenerator;
import com.ps006.upiswitch.payment.entity.TransactionEntity;
import com.ps006.upiswitch.payment.entity.TransactionEventEntity;
import com.ps006.upiswitch.payment.model.TransactionStatus;
import com.ps006.upiswitch.payment.repository.TransactionEventRepository;
import com.ps006.upiswitch.payment.repository.TransactionRepository;
import com.ps006.upiswitch.reporting.model.TxnLogRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@Transactional
public class CollectDeclineService {

    private static final Logger log = LoggerFactory.getLogger(CollectDeclineService.class);

    private final CollectRepository collectRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionEventRepository transactionEventRepository;

    public CollectDeclineService(CollectRepository collectRepository,
                                 TransactionRepository transactionRepository,
                                 TransactionEventRepository transactionEventRepository) {
        this.collectRepository = collectRepository;
        this.transactionRepository = transactionRepository;
        this.transactionEventRepository = transactionEventRepository;
    }

    /**
     * Executes COLLECT_DECLINE according to MASTER PROMPT 07.
     * Rule 6: Declined collects do not count towards velocity.
     * Zero ledger entries created; zero balance changes.
     */
    public TxnLogRow declineCollect(int lineNo, String txnId, LocalDateTime timestamp) {
        String rrn = RrnGenerator.generateRrn();

        Optional<CollectRequestEntity> collectOpt = collectRepository.findByTxnIdForUpdate(txnId);
        Optional<TransactionEntity> txnOpt = transactionRepository.findByTxnIdForUpdate(txnId);

        if (collectOpt.isEmpty() || txnOpt.isEmpty()) {
            log.warn("Collect decline failed: transaction {} not found", txnId);
            return new TxnLogRow(lineNo, txnId, "COLLECT_DECLINE", timestamp, "UNKNOWN", "UNKNOWN",
                    BigDecimal.ZERO, "FAILED", rrn, "TXN_NOT_FOUND");
        }

        CollectRequestEntity collect = collectOpt.get();
        TransactionEntity txn = txnOpt.get();

        // 1. Check if already declined (idempotent)
        if (collect.getStatus() == CollectStatus.DECLINED) {
            log.info("Duplicate decline received for already declined collect request {}", txnId);
            transactionEventRepository.save(new TransactionEventEntity(
                    txnId, "DUPLICATE_COLLECT_DECLINE", timestamp, "DECLINED", "DECLINED",
                    "Duplicate decline ignored idempotently"
            ));
            return new TxnLogRow(lineNo, txnId, "COLLECT_DECLINE", timestamp, txn.getPayerVpa(),
                    txn.getPayeeVpa(), txn.getAmount(), "DECLINED", rrn, "");
        }

        // 2. Cannot decline settled collect
        if (collect.getStatus() == CollectStatus.SUCCESS) {
            log.warn("Cannot decline already approved/settled collect request {}", txnId);
            return new TxnLogRow(lineNo, txnId, "COLLECT_DECLINE", timestamp, txn.getPayerVpa(),
                    txn.getPayeeVpa(), txn.getAmount(), "FAILED", rrn, "ALREADY_APPROVED");
        }

        // 3. Cannot decline expired collect
        if (collect.getStatus() == CollectStatus.EXPIRED || timestamp.isAfter(collect.getExpiresAt())) {
            log.warn("Cannot decline expired collect request {}", txnId);
            return new TxnLogRow(lineNo, txnId, "COLLECT_DECLINE", timestamp, txn.getPayerVpa(),
                    txn.getPayeeVpa(), txn.getAmount(), "FAILED", rrn, "ALREADY_EXPIRED");
        }

        collect.setStatus(CollectStatus.DECLINED);
        collect.setDeclinedAt(timestamp);
        collectRepository.save(collect);

        txn.setStatus(TransactionStatus.DECLINED);
        transactionRepository.save(txn);

        transactionEventRepository.save(new TransactionEventEntity(
                txnId, "COLLECT_DECLINED", timestamp, "PENDING", "DECLINED",
                "Collect request declined by payer"
        ));

        // Rule 6: Declined collects do not count towards velocity (no counter increment)
        return new TxnLogRow(lineNo, txnId, "COLLECT_DECLINE", timestamp, txn.getPayerVpa(),
                txn.getPayeeVpa(), txn.getAmount(), "DECLINED", rrn, "");
    }
}
