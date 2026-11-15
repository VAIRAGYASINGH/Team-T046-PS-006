package com.ps006.upiswitch.reversal.service;

import com.ps006.upiswitch.account.entity.AccountEntity;
import com.ps006.upiswitch.account.service.AccountService;
import com.ps006.upiswitch.config.SwitchProperties;
import com.ps006.upiswitch.counter.service.CounterService;
import com.ps006.upiswitch.ledger.model.LegType;
import com.ps006.upiswitch.ledger.service.LedgerService;
import com.ps006.upiswitch.payment.entity.TransactionEntity;
import com.ps006.upiswitch.payment.entity.TransactionEventEntity;
import com.ps006.upiswitch.payment.model.TransactionStatus;
import com.ps006.upiswitch.payment.repository.BeneficiaryPaymentHistoryRepository;
import com.ps006.upiswitch.payment.repository.TransactionEventRepository;
import com.ps006.upiswitch.payment.repository.TransactionRepository;
import com.ps006.upiswitch.vpa.model.VpaDetails;
import com.ps006.upiswitch.vpa.service.VpaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class TimeoutJobService {

    private static final Logger log = LoggerFactory.getLogger(TimeoutJobService.class);

    private final TransactionRepository transactionRepository;
    private final TransactionEventRepository transactionEventRepository;
    private final BeneficiaryPaymentHistoryRepository beneficiaryPaymentHistoryRepository;
    private final AccountService accountService;
    private final VpaService vpaService;
    private final CounterService counterService;
    private final LedgerService ledgerService;
    private final SwitchProperties switchProperties;

    public TimeoutJobService(TransactionRepository transactionRepository,
                             TransactionEventRepository transactionEventRepository,
                             BeneficiaryPaymentHistoryRepository beneficiaryPaymentHistoryRepository,
                             AccountService accountService,
                             VpaService vpaService,
                             CounterService counterService,
                             LedgerService ledgerService,
                             SwitchProperties switchProperties) {
        this.transactionRepository = transactionRepository;
        this.transactionEventRepository = transactionEventRepository;
        this.beneficiaryPaymentHistoryRepository = beneficiaryPaymentHistoryRepository;
        this.accountService = accountService;
        this.vpaService = vpaService;
        this.counterService = counterService;
        this.ledgerService = ledgerService;
        this.switchProperties = switchProperties;
    }

    /**
     * Executes automatic timeout processing and reversals for transactions that have remained
     * in PENDING status for at least 30 minutes (BR-05).
     *
     * Order of operations:
     * 1. Lock the transaction individually with pessimistic write lock.
     * 2. Verify it is still in PENDING status.
     * 3. Verify the 30-minute threshold (elapsed seconds >= 1,800).
     * 4. Credit remitter account.
     * 5. Create append-only REVERSAL ledger entry.
     * 6. Restore eligible daily counters (daily_debit_amount and daily_debit_count).
     * 7. Restore monthly counter and beneficiary cooling count.
     * 8. Mark transaction status as REVERSED.
     * 9. Record reversal timestamp.
     *
     * @param jobTimestamp Timestamp at which the timeout job executes.
     * @return Number of transactions successfully reversed.
     */
    public int processTimeouts(LocalDateTime jobTimestamp) {
        long timeoutMinutes = switchProperties.getTimeout().getPendingPaymentTimeoutMinutes();
        LocalDateTime cutoff = jobTimestamp.minusMinutes(timeoutMinutes);

        List<TransactionEntity> candidates = transactionRepository
                .findByStatusAndInitiatedAtBefore(TransactionStatus.PENDING, cutoff);

        int reversedCount = 0;

        for (TransactionEntity candidate : candidates) {
            // 1. Lock the transaction
            Optional<TransactionEntity> lockedTxnOpt = transactionRepository.findByTxnIdForUpdate(candidate.getTxnId());
            if (lockedTxnOpt.isEmpty()) {
                log.warn("Candidate transaction {} not found during lock acquisition", candidate.getTxnId());
                continue;
            }
            TransactionEntity txn = lockedTxnOpt.get();

            // 2. Verify it is still PENDING under row lock
            if (txn.getStatus() != TransactionStatus.PENDING) {
                log.warn("Transaction {} is no longer PENDING (current status: {}), skipping timeout reversal",
                        txn.getTxnId(), txn.getStatus());
                continue;
            }

            // 3. Verify the 30-minute threshold: Duration in seconds >= timeoutMinutes * 60
            long elapsedSeconds = Duration.between(txn.getInitiatedAt(), jobTimestamp).toSeconds();
            if (elapsedSeconds < timeoutMinutes * 60L) {
                log.debug("Transaction {} has not reached timeout threshold (elapsed: {}s < {}s), skipping",
                        txn.getTxnId(), elapsedSeconds, timeoutMinutes * 60L);
                continue;
            }

            log.info("Auto-reversing timed-out transaction {} (elapsed {}s >= {}s)",
                    txn.getTxnId(), elapsedSeconds, timeoutMinutes * 60L);

            VpaDetails payerVpa = vpaService.getVpaOrThrow(txn.getPayerVpa());

            // 4. Credit remitter account
            AccountEntity payerAcc = accountService.creditAccount(payerVpa.accountId(), txn.getAmount());

            // 5. Create append-only REVERSAL ledger entry
            ledgerService.recordEntry(
                    txn.getTxnId(), payerVpa.accountId(), txn.getPayerVpa(),
                    LegType.REVERSAL, null, txn.getAmount(), payerAcc.getBalance(),
                    jobTimestamp, null, "Timeout auto-reversal for " + txn.getTxnId()
            );

            // 6. Restore eligible daily counters and velocity count
            counterService.decrementDaily(payerVpa.ownerId(), txn.getInitiatedAt().toLocalDate(), txn.getAmount());

            // 7. Restore monthly counter and beneficiary cooling count
            counterService.decrementMonthly(payerVpa.ownerId(), txn.getInitiatedAt().toLocalDate(), txn.getAmount());
            beneficiaryPaymentHistoryRepository.findByPayerVpaAndPayeeVpaForUpdate(txn.getPayerVpa(), txn.getPayeeVpa())
                    .ifPresent(h -> {
                        h.setPaymentCount(Math.max(0, h.getPaymentCount() - 1));
                        beneficiaryPaymentHistoryRepository.save(h);
                    });

            // 8. Mark transaction REVERSED
            txn.setStatus(TransactionStatus.REVERSED);

            // 9. Record reversal timestamp
            txn.setReversedAt(jobTimestamp);
            transactionRepository.save(txn);

            transactionEventRepository.save(new TransactionEventEntity(
                    txn.getTxnId(), "TIMEOUT_AUTO_REVERSAL", jobTimestamp,
                    "PENDING", "REVERSED", "Timed out after 30 minutes, payer refunded"
            ));

            reversedCount++;
        }

        return reversedCount;
    }
}
