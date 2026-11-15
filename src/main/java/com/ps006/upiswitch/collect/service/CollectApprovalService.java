package com.ps006.upiswitch.collect.service;

import com.ps006.upiswitch.account.entity.AccountEntity;
import com.ps006.upiswitch.account.model.AccountDetails;
import com.ps006.upiswitch.account.service.AccountService;
import com.ps006.upiswitch.collect.entity.CollectRequestEntity;
import com.ps006.upiswitch.collect.model.CollectStatus;
import com.ps006.upiswitch.collect.repository.CollectRepository;
import com.ps006.upiswitch.common.util.RrnGenerator;
import com.ps006.upiswitch.counter.model.DailyCounterModel;
import com.ps006.upiswitch.counter.service.CounterService;
import com.ps006.upiswitch.ledger.service.LedgerService;
import com.ps006.upiswitch.merchant.model.MerchantProfile;
import com.ps006.upiswitch.merchant.service.MerchantService;
import com.ps006.upiswitch.payment.entity.BeneficiaryPaymentHistoryEntity;
import com.ps006.upiswitch.payment.entity.TransactionEntity;
import com.ps006.upiswitch.payment.entity.TransactionEventEntity;
import com.ps006.upiswitch.payment.model.TransactionStatus;
import com.ps006.upiswitch.payment.model.TransactionType;
import com.ps006.upiswitch.payment.repository.BeneficiaryPaymentHistoryRepository;
import com.ps006.upiswitch.payment.repository.TransactionEventRepository;
import com.ps006.upiswitch.payment.repository.TransactionRepository;
import com.ps006.upiswitch.reporting.model.TxnLogRow;
import com.ps006.upiswitch.user.model.UserProfile;
import com.ps006.upiswitch.user.service.UserService;
import com.ps006.upiswitch.validation.model.PaymentValidationContext;
import com.ps006.upiswitch.validation.model.ValidationResult;
import com.ps006.upiswitch.validation.service.ValidationPipelineService;
import com.ps006.upiswitch.vpa.model.OwnerType;
import com.ps006.upiswitch.vpa.model.VpaDetails;
import com.ps006.upiswitch.vpa.service.VpaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@Transactional
public class CollectApprovalService {

    private static final Logger log = LoggerFactory.getLogger(CollectApprovalService.class);

    private final CollectRepository collectRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionEventRepository transactionEventRepository;
    private final BeneficiaryPaymentHistoryRepository beneficiaryPaymentHistoryRepository;
    private final CollectExpiryService collectExpiryService;
    private final VpaService vpaService;
    private final AccountService accountService;
    private final UserService userService;
    private final MerchantService merchantService;
    private final CounterService counterService;
    private final LedgerService ledgerService;
    private final ValidationPipelineService validationPipelineService;

    public CollectApprovalService(CollectRepository collectRepository,
                                  TransactionRepository transactionRepository,
                                  TransactionEventRepository transactionEventRepository,
                                  BeneficiaryPaymentHistoryRepository beneficiaryPaymentHistoryRepository,
                                  CollectExpiryService collectExpiryService,
                                  VpaService vpaService,
                                  AccountService accountService,
                                  UserService userService,
                                  MerchantService merchantService,
                                  CounterService counterService,
                                  LedgerService ledgerService,
                                  ValidationPipelineService validationPipelineService) {
        this.collectRepository = collectRepository;
        this.transactionRepository = transactionRepository;
        this.transactionEventRepository = transactionEventRepository;
        this.beneficiaryPaymentHistoryRepository = beneficiaryPaymentHistoryRepository;
        this.collectExpiryService = collectExpiryService;
        this.vpaService = vpaService;
        this.accountService = accountService;
        this.userService = userService;
        this.merchantService = merchantService;
        this.counterService = counterService;
        this.ledgerService = ledgerService;
        this.validationPipelineService = validationPipelineService;
    }

    /**
     * Executes COLLECT_APPROVE according to MASTER PROMPT 07.
     * Reuses the existing payment validation engine without duplicating limit logic.
     * Applies all applicable payment limits (BR-01, BR-02, BR-03, BR-04, BR-05, BR-06).
     * Enforces idempotency (prevents duplicate approvals, prevents approval after decline or expiry).
     */
    public TxnLogRow approveCollect(int lineNo, String txnId, LocalDateTime timestamp) {
        String rrn = RrnGenerator.generateRrn();

        // 1. Acquire row-level pessimistic locks
        Optional<CollectRequestEntity> collectOpt = collectRepository.findByTxnIdForUpdate(txnId);
        Optional<TransactionEntity> txnOpt = transactionRepository.findByTxnIdForUpdate(txnId);

        if (collectOpt.isEmpty() || txnOpt.isEmpty()) {
            log.warn("Collect approval failed: transaction {} not found", txnId);
            return new TxnLogRow(lineNo, txnId, "COLLECT_APPROVE", timestamp, "UNKNOWN", "UNKNOWN",
                    BigDecimal.ZERO, "FAILED", rrn, "TXN_NOT_FOUND");
        }

        CollectRequestEntity collect = collectOpt.get();
        TransactionEntity txn = txnOpt.get();

        // 2. Rule 9: Prevent duplicate approvals
        if (collect.getStatus() == CollectStatus.SUCCESS) {
            log.warn("Duplicate approval attempt for already approved collect request {}", txnId);
            transactionEventRepository.save(new TransactionEventEntity(
                    txnId, "DUPLICATE_COLLECT_APPROVE", timestamp, "SUCCESS", "SUCCESS",
                    "Duplicate approval ignored idempotently"
            ));
            return new TxnLogRow(lineNo, txnId, "COLLECT_APPROVE", timestamp, txn.getPayerVpa(),
                    txn.getPayeeVpa(), txn.getAmount(), "SUCCESS", rrn, "");
        }

        // 3. Rule 10: Prevent approval after decline
        if (collect.getStatus() == CollectStatus.DECLINED) {
            log.warn("Approval rejected: collect request {} was already declined", txnId);
            transactionEventRepository.save(new TransactionEventEntity(
                    txnId, "COLLECT_APPROVE_AFTER_DECLINE", timestamp, "DECLINED", "DECLINED",
                    "Approval rejected because request was already declined"
            ));
            return new TxnLogRow(lineNo, txnId, "COLLECT_APPROVE", timestamp, txn.getPayerVpa(),
                    txn.getPayeeVpa(), txn.getAmount(), "DECLINED", rrn, "ALREADY_DECLINED");
        }

        // 4. Rule 5 & 11: Prevent approval after expiry
        if (collect.getStatus() == CollectStatus.EXPIRED || collectExpiryService.isExpired(collect, timestamp)) {
            log.info("Approval rejected: collect request {} expired at {} (approval attempted at {})",
                    txnId, collect.getExpiresAt(), timestamp);
            collectExpiryService.markExpired(collect, txn, timestamp);
            return new TxnLogRow(lineNo, txnId, "COLLECT_APPROVE", timestamp, txn.getPayerVpa(),
                    txn.getPayeeVpa(), txn.getAmount(), "EXPIRED", rrn, "");
        }

        if (collect.getStatus() != CollectStatus.PENDING) {
            log.warn("Collect request {} not in PENDING state (current: {})", txnId, collect.getStatus());
            return new TxnLogRow(lineNo, txnId, "COLLECT_APPROVE", timestamp, txn.getPayerVpa(),
                    txn.getPayeeVpa(), txn.getAmount(), "FAILED", rrn, "INVALID_STATE");
        }

        // 5. Rule 8: Apply all applicable payment limits during approval using ValidationPipelineService
        VpaDetails payerVpa = vpaService.getVpaOrThrow(txn.getPayerVpa());
        VpaDetails payeeVpa = vpaService.getVpaOrThrow(txn.getPayeeVpa());

        UserProfile payerUser = userService.findById(payerVpa.ownerId()).orElse(null);
        MerchantProfile payeeMerchant = payeeVpa.ownerType() == OwnerType.MERCHANT
                ? merchantService.findById(payeeVpa.ownerId()).orElse(null)
                : null;
        AccountDetails payerAccount = accountService.findById(payerVpa.accountId()).orElse(null);
        DailyCounterModel payerDailyCounter = counterService.getDailyCounter(payerVpa.ownerId(), timestamp.toLocalDate());
        BeneficiaryPaymentHistoryEntity history = beneficiaryPaymentHistoryRepository
                .findByPayerVpaAndPayeeVpa(txn.getPayerVpa(), txn.getPayeeVpa()).orElse(null);

        TransactionType txnType = payeeMerchant != null ? TransactionType.P2M : TransactionType.P2P;

        PaymentValidationContext context = new PaymentValidationContext(
                txnId, txn.getPayerVpa(), txn.getPayeeVpa(), txn.getAmount(), timestamp,
                txnType, payerVpa, payeeVpa, payerUser, payeeMerchant, payerAccount,
                payerDailyCounter, history
        );

        ValidationResult structResult = validationPipelineService.validateIdentityAndStructure(context);
        if (!structResult.valid()) {
            log.warn("Collect approval structural validation failed: {}", structResult.failedRuleIds());
            return rejectApproval(lineNo, txnId, timestamp, rrn, collect, txn, structResult);
        }

        ValidationResult ruleResult = validationPipelineService.validate(context);
        if (!ruleResult.valid()) {
            log.warn("Collect approval business rule limits breached: {}", ruleResult.failedRuleIds());
            return rejectApproval(lineNo, txnId, timestamp, rrn, collect, txn, ruleResult);
        }

        // 6. Concurrency lock ordering: Lock both accounts in deterministic order
        long firstLockId = Math.min(payerVpa.accountId(), payeeVpa.accountId());
        long secondLockId = Math.max(payerVpa.accountId(), payeeVpa.accountId());
        accountService.getEntityForUpdate(firstLockId);
        accountService.getEntityForUpdate(secondLockId);

        // Verify balance under lock
        AccountEntity payerAccEntity = accountService.getEntityForUpdate(payerVpa.accountId());
        if (payerAccEntity.getBalance().compareTo(txn.getAmount()) < 0) {
            log.warn("Collect approval balance check failed under lock for account {}", payerVpa.accountId());
            ValidationResult balFail = ValidationResult.failure("BR-06", "U16", "Insufficient funds");
            return rejectApproval(lineNo, txnId, timestamp, rrn, collect, txn, balFail);
        }

        // 7. Atomic debit remitter and credit payee
        AccountEntity payerAcc = accountService.debitAccount(payerVpa.accountId(), txn.getAmount());
        ledgerService.recordDebit(txnId, payerVpa.accountId(), txn.getPayerVpa(), txn.getAmount(),
                payerAcc.getBalance(), timestamp, "Collect payment to " + txn.getPayeeVpa());

        AccountEntity payeeAcc = accountService.creditAccount(payeeVpa.accountId(), txn.getAmount());
        ledgerService.recordCredit(txnId, payeeVpa.accountId(), txn.getPayeeVpa(), txn.getAmount(),
                payeeAcc.getBalance(), timestamp, "Collect payment from " + txn.getPayerVpa());

        // 8. Rule 7: Approved collects count as successful debits
        counterService.incrementDaily(payerVpa.ownerId(), timestamp.toLocalDate(), txn.getAmount());
        counterService.incrementMonthly(payerVpa.ownerId(), timestamp.toLocalDate(), txn.getAmount());

        // Update beneficiary history
        if (history == null) {
            beneficiaryPaymentHistoryRepository.save(new BeneficiaryPaymentHistoryEntity(
                    txn.getPayerVpa(), txn.getPayeeVpa(), timestamp, 1, timestamp
            ));
        } else {
            history.setPaymentCount(history.getPaymentCount() + 1);
            history.setLastPaymentAt(timestamp);
            beneficiaryPaymentHistoryRepository.save(history);
        }

        // 9. Update state to SUCCESS
        collect.setStatus(CollectStatus.SUCCESS);
        collect.setApprovedAt(timestamp);
        collectRepository.save(collect);

        txn.setStatus(TransactionStatus.SUCCESS);
        transactionRepository.save(txn);

        transactionEventRepository.save(new TransactionEventEntity(
                txnId, "COLLECT_APPROVED", timestamp, "PENDING", "SUCCESS",
                "Collect request approved and settled"
        ));

        return new TxnLogRow(lineNo, txnId, "COLLECT_APPROVE", timestamp, txn.getPayerVpa(),
                txn.getPayeeVpa(), txn.getAmount(), "SUCCESS", rrn, "");
    }

    private TxnLogRow rejectApproval(int lineNo, String txnId, LocalDateTime timestamp, String rrn,
                                     CollectRequestEntity collect, TransactionEntity txn,
                                     ValidationResult result) {
        collect.setStatus(CollectStatus.FAILED);
        collectRepository.save(collect);

        txn.setStatus(TransactionStatus.FAILED);
        txn.setRuleIds(result.ruleIdsString());
        transactionRepository.save(txn);

        transactionEventRepository.save(new TransactionEventEntity(
                txnId, "COLLECT_APPROVE_FAILED", timestamp, "PENDING", "FAILED",
                "Approval failed limits: " + result.failedRuleIds()
        ));

        String primaryRule = result.failedRuleIds().isEmpty() ? "FAILED" : result.failedRuleIds().get(0);
        return new TxnLogRow(lineNo, txnId, "COLLECT_APPROVE", timestamp, txn.getPayerVpa(),
                txn.getPayeeVpa(), txn.getAmount(), "FAILED", rrn, primaryRule);
    }
}

