package com.ps006.upiswitch.dispute.service;

import com.ps006.upiswitch.account.entity.AccountEntity;
import com.ps006.upiswitch.account.repository.AccountRepository;
import com.ps006.upiswitch.dispute.entity.DisputeEntity;
import com.ps006.upiswitch.ledger.model.LegType;
import com.ps006.upiswitch.ledger.service.LedgerService;
import com.ps006.upiswitch.payment.entity.TransactionEntity;
import com.ps006.upiswitch.payment.repository.TransactionRepository;
import com.ps006.upiswitch.vpa.entity.VpaEntity;
import com.ps006.upiswitch.vpa.repository.VpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Handles financial settlement for disputes when an explicit settlement outcome occurs.
 * Kept completely decoupled from dispute workflow state transitions.
 * Strictly adheres to double-entry bookkeeping and deadlock-free multi-account locking.
 */
@Service
@Transactional
public class DisputeSettlementService {

    private static final Logger log = LoggerFactory.getLogger(DisputeSettlementService.class);

    private final AccountRepository accountRepository;
    private final LedgerService ledgerService;
    private final TransactionRepository transactionRepository;
    private final VpaRepository vpaRepository;

    public DisputeSettlementService(AccountRepository accountRepository,
                                  LedgerService ledgerService,
                                  TransactionRepository transactionRepository,
                                  VpaRepository vpaRepository) {
        this.accountRepository = accountRepository;
        this.ledgerService = ledgerService;
        this.transactionRepository = transactionRepository;
        this.vpaRepository = vpaRepository;
    }

    /**
     * Executes financial settlement for an accepted chargeback claim.
     * Debits the payee (DISPUTE_DEBIT) and credits the remitter (DISPUTE_CREDIT).
     * Locks accounts in ascending numerical ID order to prevent deadlocks.
     */
    public boolean settleChargebackAcceptance(DisputeEntity dispute, LocalDateTime timestamp) {
        if (dispute == null) return false;

        TransactionEntity txn = transactionRepository.findByTxnId(dispute.getTxnId()).orElse(null);
        if (txn == null) {
            log.warn("Cannot settle dispute {}: transaction {} not found", dispute.getDisputeId(), dispute.getTxnId());
            return false;
        }

        BigDecimal amount = dispute.getAmount();

        // 1. Resolve remitter account (credited on dispute accept)
        VpaEntity remitterVpa = vpaRepository.findByVpa(txn.getPayerVpa()).orElse(null);
        if (remitterVpa == null || remitterVpa.getAccountId() == null) {
            log.warn("Cannot settle dispute {}: remitter VPA {} has no linked account", dispute.getDisputeId(), txn.getPayerVpa());
            return false;
        }
        AccountEntity remitterAccount = accountRepository.findById(remitterVpa.getAccountId()).orElse(null);
        if (remitterAccount == null) {
            log.warn("Cannot settle dispute {}: remitter account {} not found", dispute.getDisputeId(), remitterVpa.getAccountId());
            return false;
        }

        // 2. Resolve payee account (debited on dispute accept)
        VpaEntity payeeVpa = vpaRepository.findByVpa(txn.getPayeeVpa()).orElse(null);
        AccountEntity payeeAccount = null;
        if (payeeVpa != null && payeeVpa.getAccountId() != null) {
            payeeAccount = accountRepository.findById(payeeVpa.getAccountId()).orElse(null);
        }

        // If payee account is absent (e.g., external merchant), credit remitter directly
        if (payeeAccount == null) {
            log.info("Payee account not found for {} (merchant/external); crediting remitter directly", txn.getPayeeVpa());
            remitterAccount.setBalance(remitterAccount.getBalance().add(amount));
            accountRepository.save(remitterAccount);
            ledgerService.recordEntry(
                    dispute.getTxnId(),
                    remitterAccount.getAccountId(),
                    txn.getPayerVpa(),
                    LegType.DISPUTE_CREDIT,
                    null,
                    amount,
                    remitterAccount.getBalance(),
                    timestamp,
                    null,
                    "Dispute acceptance settlement credit: " + dispute.getDisputeId()
            );
            return true;
        }

        // Both accounts exist: acquire locks in ascending order of ID to prevent deadlocks
        Long firstAccountId = Math.min(remitterAccount.getAccountId(), payeeAccount.getAccountId());
        Long secondAccountId = Math.max(remitterAccount.getAccountId(), payeeAccount.getAccountId());

        Optional<AccountEntity> firstOpt = accountRepository.findByIdForUpdate(firstAccountId);
        Optional<AccountEntity> secondOpt = accountRepository.findByIdForUpdate(secondAccountId);

        if (firstOpt.isEmpty() || secondOpt.isEmpty()) {
            log.warn("Cannot acquire lock for settlement: account {} or {} not found for update", firstAccountId, secondAccountId);
            return false;
        }

        AccountEntity first = firstOpt.get();
        AccountEntity second = secondOpt.get();

        AccountEntity lockedRemitter = first.getAccountId().equals(remitterAccount.getAccountId()) ? first : second;
        AccountEntity lockedPayee = first.getAccountId().equals(payeeAccount.getAccountId()) ? first : second;

        // Debit payee
        lockedPayee.setBalance(lockedPayee.getBalance().subtract(amount));
        accountRepository.save(lockedPayee);

        ledgerService.recordEntry(
                dispute.getTxnId(),
                lockedPayee.getAccountId(),
                txn.getPayeeVpa(),
                LegType.DISPUTE_DEBIT,
                amount,
                null,
                lockedPayee.getBalance(),
                timestamp,
                null,
                "Dispute acceptance settlement debit: " + dispute.getDisputeId()
        );

        // Credit remitter
        lockedRemitter.setBalance(lockedRemitter.getBalance().add(amount));
        accountRepository.save(lockedRemitter);

        ledgerService.recordEntry(
                dispute.getTxnId(),
                lockedRemitter.getAccountId(),
                txn.getPayerVpa(),
                LegType.DISPUTE_CREDIT,
                null,
                amount,
                lockedRemitter.getBalance(),
                timestamp,
                null,
                "Dispute acceptance settlement credit: " + dispute.getDisputeId()
        );

        log.info("Dispute {} settled successfully: debited {} ₹{}, credited {} ₹{}",
                dispute.getDisputeId(), txn.getPayeeVpa(), amount, txn.getPayerVpa(), amount);
        return true;
    }
}
