package com.ps006.upiswitch.payment.service;

import com.ps006.upiswitch.account.entity.AccountEntity;
import com.ps006.upiswitch.account.model.AccountDetails;
import com.ps006.upiswitch.account.service.AccountService;
import com.ps006.upiswitch.common.entity.ReconciliationRecordEntity;
import com.ps006.upiswitch.common.repository.ReconciliationRecordRepository;
import com.ps006.upiswitch.common.util.RrnGenerator;
import com.ps006.upiswitch.counter.model.DailyCounterModel;
import com.ps006.upiswitch.counter.service.CounterService;
import com.ps006.upiswitch.ledger.model.LegType;
import com.ps006.upiswitch.ledger.service.LedgerService;
import com.ps006.upiswitch.merchant.model.MerchantProfile;
import com.ps006.upiswitch.merchant.service.MerchantService;
import com.ps006.upiswitch.payment.entity.BeneficiaryPaymentHistoryEntity;
import com.ps006.upiswitch.payment.entity.TransactionEntity;
import com.ps006.upiswitch.payment.entity.TransactionEventEntity;
import com.ps006.upiswitch.payment.model.PaymentResult;
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
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final TransactionRepository transactionRepository;
    private final TransactionEventRepository transactionEventRepository;
    private final BeneficiaryPaymentHistoryRepository beneficiaryPaymentHistoryRepository;
    private final ReconciliationRecordRepository reconciliationRecordRepository;
    private final VpaService vpaService;
    private final AccountService accountService;
    private final UserService userService;
    private final MerchantService merchantService;
    private final CounterService counterService;
    private final LedgerService ledgerService;
    private final ValidationPipelineService validationPipelineService;

    public PaymentService(TransactionRepository transactionRepository,
                          TransactionEventRepository transactionEventRepository,
                          BeneficiaryPaymentHistoryRepository beneficiaryPaymentHistoryRepository,
                          ReconciliationRecordRepository reconciliationRecordRepository,
                          VpaService vpaService,
                          AccountService accountService,
                          UserService userService,
                          MerchantService merchantService,
                          CounterService counterService,
                          LedgerService ledgerService,
                          ValidationPipelineService validationPipelineService) {
        this.transactionRepository = transactionRepository;
        this.transactionEventRepository = transactionEventRepository;
        this.beneficiaryPaymentHistoryRepository = beneficiaryPaymentHistoryRepository;
        this.reconciliationRecordRepository = reconciliationRecordRepository;
        this.vpaService = vpaService;
        this.accountService = accountService;
        this.userService = userService;
        this.merchantService = merchantService;
        this.counterService = counterService;
        this.ledgerService = ledgerService;
        this.validationPipelineService = validationPipelineService;
    }

    /**
     * Executes the payment authorization engine returning a structured PaymentResult.
     */
    public PaymentResult processPaymentResult(String txnId, LocalDateTime timestamp,
                                             String payerVpa, String payeeVpa,
                                             BigDecimal amount, String remarks) {
        String rrn = RrnGenerator.generateRrn();

        // 0. Defensive structural checks on parameters
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Invalid non-positive transaction amount: {}", amount);
            return PaymentResult.failure(txnId, rrn, List.of("INVALID_AMOUNT"), "AM02", timestamp);
        }

        BigDecimal stdAmount = amount.setScale(2, RoundingMode.HALF_UP);

        if (payerVpa == null || payerVpa.trim().isEmpty() || payeeVpa == null || payeeVpa.trim().isEmpty()) {
            log.warn("Missing or empty VPA in payment request: payer={}, payee={}", payerVpa, payeeVpa);
            return PaymentResult.failure(txnId, rrn, List.of("INVALID_VPA"), "U02", timestamp);
        }

        if (payerVpa.trim().equalsIgnoreCase(payeeVpa.trim())) {
            log.warn("Self transfer attempted: {}", payerVpa);
            return PaymentResult.failure(txnId, rrn, List.of("SELF_TRANSFER_NOT_ALLOWED"), "U03", timestamp);
        }

        // 1. Idempotency check: Reject duplicate txn_id
        if (transactionRepository.findByTxnId(txnId).isPresent()) {
            log.warn("Duplicate txn_id detected: {}", txnId);
            reconciliationRecordRepository.save(new ReconciliationRecordEntity(
                    txnId, "DUPLICATE_REPLAY", timestamp, stdAmount, payerVpa, payeeVpa,
                    BigDecimal.ZERO, "PENDING", "Duplicate txn_id replayed"
            ));
            return PaymentResult.failure(txnId, rrn, List.of("DUPLICATE_TXN_ID"), "U01", timestamp);
        }

        // 2. Resolve VPAs
        Optional<VpaDetails> payerVpaOpt = vpaService.findByVpa(payerVpa);
        Optional<VpaDetails> payeeVpaOpt = vpaService.findByVpa(payeeVpa);

        if (payerVpaOpt.isEmpty() || payeeVpaOpt.isEmpty()) {
            TransactionType type = (payeeVpaOpt.isPresent() && payeeVpaOpt.get().ownerType() == OwnerType.MERCHANT)
                    ? TransactionType.P2M : TransactionType.P2P;
            String errorRule = payerVpaOpt.isEmpty() ? "PAYER_NOT_FOUND" : "PAYEE_NOT_FOUND";
            TransactionEntity txn = new TransactionEntity(
                    txnId, rrn, "PAY", payerVpa, payeeVpa, stdAmount, remarks,
                    type, TransactionStatus.FAILED, errorRule, BigDecimal.ZERO,
                    timestamp, null, null
            );
            transactionRepository.save(txn);
            return PaymentResult.failure(txnId, rrn, List.of(errorRule), "U02", timestamp);
        }

        VpaDetails payerVpaDetails = payerVpaOpt.get();
        VpaDetails payeeVpaDetails = payeeVpaOpt.get();

        UserProfile payerUser = userService.findById(payerVpaDetails.ownerId()).orElse(null);
        MerchantProfile payeeMerchant = payeeVpaDetails.ownerType() == OwnerType.MERCHANT
                ? merchantService.findById(payeeVpaDetails.ownerId()).orElse(null)
                : null;
        AccountDetails payerAccount = accountService.findById(payerVpaDetails.accountId()).orElse(null);
        DailyCounterModel payerDailyCounter = counterService.getDailyCounter(payerVpaDetails.ownerId(), timestamp.toLocalDate());
        BeneficiaryPaymentHistoryEntity history = beneficiaryPaymentHistoryRepository
                .findByPayerVpaAndPayeeVpa(payerVpa, payeeVpa).orElse(null);

        TransactionType txnType = payeeMerchant != null ? TransactionType.P2M : TransactionType.P2P;

        // 3. Build validation context
        PaymentValidationContext context = new PaymentValidationContext(
                txnId, payerVpa, payeeVpa, stdAmount, timestamp, txnType,
                payerVpaDetails, payeeVpaDetails, payerUser, payeeMerchant,
                payerAccount, payerDailyCounter, history
        );

        // 4. Structural validation
        ValidationResult structResult = validationPipelineService.validateIdentityAndStructure(context);
        if (!structResult.valid()) {
            TransactionEntity txn = new TransactionEntity(
                    txnId, rrn, "PAY", payerVpa, payeeVpa, stdAmount, remarks,
                    txnType, TransactionStatus.FAILED, structResult.ruleIdsString(), BigDecimal.ZERO,
                    timestamp, null, null
            );
            transactionRepository.save(txn);
            return PaymentResult.failure(txnId, rrn, structResult.failedRuleIds(), structResult.errorCode(), timestamp);
        }

        // 5. Business Rule Validation: Evaluates BR-01 -> BR-02 -> BR-03 -> BR-04 -> BR-05 -> BR-06
        // Non-short-circuiting: collects all failed rule IDs in deterministic order.
        ValidationResult ruleResult = validationPipelineService.validate(context);

        if (!ruleResult.valid()) {
            TransactionEntity txn = new TransactionEntity(
                    txnId, rrn, "PAY", payerVpa, payeeVpa, stdAmount, remarks,
                    txnType, TransactionStatus.FAILED, ruleResult.ruleIdsString(), BigDecimal.ZERO,
                    timestamp, null, null
            );
            transactionRepository.save(txn);
            transactionEventRepository.save(new TransactionEventEntity(
                    txnId, "PAY_FAILED", timestamp, null, "FAILED", ruleResult.message()
            ));
            return PaymentResult.failure(txnId, rrn, ruleResult.failedRuleIds(), ruleResult.errorCode(), timestamp);
        }

        // 6. Validation passed! Lock remitter account and verify latest balance
        AccountEntity payerAccountEntity = accountService.getEntityForUpdate(payerVpaDetails.accountId());
        if (payerAccountEntity.getBalance().compareTo(stdAmount) < 0) {
            // Concurrency boundary: balance became insufficient while waiting for lock
            TransactionEntity txn = new TransactionEntity(
                    txnId, rrn, "PAY", payerVpa, payeeVpa, stdAmount, remarks,
                    txnType, TransactionStatus.FAILED, "BR-06", BigDecimal.ZERO,
                    timestamp, null, null
            );
            transactionRepository.save(txn);
            return PaymentResult.failure(txnId, rrn, List.of("BR-06"), "U16", timestamp);
        }

        // 7. Debit remitter account
        AccountEntity updatedPayer = accountService.debitAccount(payerVpaDetails.accountId(), stdAmount);

        // 8. Create debit ledger entry (append-only)
        ledgerService.recordDebit(txnId, payerVpaDetails.accountId(), payerVpa, stdAmount,
                updatedPayer.getBalance(), timestamp, remarks != null ? remarks : "PAYMENT_DEBIT");

        // 9. Update daily and monthly counters transactionally
        counterService.incrementDaily(payerVpaDetails.ownerId(), timestamp.toLocalDate(), stdAmount);
        counterService.incrementMonthly(payerVpaDetails.ownerId(), timestamp.toLocalDate(), stdAmount);

        // 10. Update or initialize beneficiary payment history
        if (history == null) {
            beneficiaryPaymentHistoryRepository.save(new BeneficiaryPaymentHistoryEntity(
                    payerVpa, payeeVpa, timestamp, 1, timestamp
            ));
        } else {
            history.setPaymentCount(history.getPaymentCount() + 1);
            history.setLastPaymentAt(timestamp);
            beneficiaryPaymentHistoryRepository.save(history);
        }

        // 11. Create transaction in PENDING state
        TransactionEntity txn = new TransactionEntity(
                txnId, rrn, "PAY", payerVpa, payeeVpa, stdAmount, remarks,
                txnType, TransactionStatus.PENDING, null, BigDecimal.ZERO,
                timestamp, null, null
        );
        transactionRepository.save(txn);
        transactionEventRepository.save(new TransactionEventEntity(
                txnId, "PAY_INITIATED", timestamp, null, "PENDING", "Payment initiated"
        ));

        return PaymentResult.pending(txnId, rrn, timestamp);
    }

    /**
     * Ingestion adapter for command file processing emitting TxnLogRow.
     */
    public TxnLogRow processPayment(int lineNo, String txnId, LocalDateTime timestamp,
                                    String payerVpa, String payeeVpa, BigDecimal amount, String remarks) {
        PaymentResult result = processPaymentResult(txnId, timestamp, payerVpa, payeeVpa, amount, remarks);
        BigDecimal stdAmount = amount.setScale(2, RoundingMode.UNNECESSARY);
        String statusStr = result.status().name();
        String ruleIdsStr = result.getPrimaryRuleId(); // Emit primary failed rule ID in txn_log.csv

        return new TxnLogRow(lineNo, txnId, "PAY", timestamp, payerVpa, payeeVpa, stdAmount, statusStr, result.rrn(), ruleIdsStr);
    }

    /**
     * Handles external beneficiary responses (ACK, NACK, NO_RESPONSE).
     */
    public void handleBeneficiaryResponse(String txnId, LocalDateTime timestamp, String responseCode) {
        Optional<TransactionEntity> txnOpt = transactionRepository.findByTxnIdForUpdate(txnId);

        if (txnOpt.isEmpty()) {
            // Out of order packet! Response arrived before transaction initiation (Line 115 TXN-0038)
            log.warn("Out-of-order packet received for non-existent txn {}", txnId);
            reconciliationRecordRepository.save(new ReconciliationRecordEntity(
                    txnId, "OUT_OF_ORDER_PACKET", timestamp, BigDecimal.ZERO,
                    "UNKNOWN", "UNKNOWN", BigDecimal.ZERO, "PENDING",
                    "BENE_RESPONSE received before PAY for " + txnId
            ));
            return;
        }

        TransactionEntity txn = txnOpt.get();

        if (txn.getStatus() == TransactionStatus.PENDING) {
            if ("ACK".equalsIgnoreCase(responseCode)) {
                // Credit beneficiary account
                VpaDetails payeeVpaDetails = vpaService.getVpaOrThrow(txn.getPayeeVpa());
                AccountEntity payeeAccount = accountService.creditAccount(payeeVpaDetails.accountId(), txn.getAmount());

                ledgerService.recordCredit(txnId, payeeVpaDetails.accountId(), txn.getPayeeVpa(),
                        txn.getAmount(), payeeAccount.getBalance(), timestamp, "Payment from " + txn.getPayerVpa());

                txn.setStatus(TransactionStatus.SUCCESS);
                transactionRepository.save(txn);
                transactionEventRepository.save(new TransactionEventEntity(
                        txnId, "BENE_ACK", timestamp, "PENDING", "SUCCESS", "Beneficiary credited successfully"
                ));
            } else if ("NACK".equalsIgnoreCase(responseCode)) {
                // Beneficiary declined - refund remitter and restore counters
                VpaDetails payerVpaDetails = vpaService.getVpaOrThrow(txn.getPayerVpa());
                AccountEntity payerAccount = accountService.creditAccount(payerVpaDetails.accountId(), txn.getAmount());

                ledgerService.recordEntry(txnId, payerVpaDetails.accountId(), txn.getPayerVpa(),
                        LegType.REVERSAL, null, txn.getAmount(), payerAccount.getBalance(),
                        timestamp, null, "NACK reversal for " + txnId);

                // Restore daily and monthly counters exactly once
                counterService.decrementDaily(payerVpaDetails.ownerId(), txn.getInitiatedAt().toLocalDate(), txn.getAmount());
                counterService.decrementMonthly(payerVpaDetails.ownerId(), txn.getInitiatedAt().toLocalDate(), txn.getAmount());

                // Restore beneficiary payment history count
                beneficiaryPaymentHistoryRepository.findByPayerVpaAndPayeeVpa(txn.getPayerVpa(), txn.getPayeeVpa())
                        .ifPresent(h -> {
                            h.setPaymentCount(Math.max(0, h.getPaymentCount() - 1));
                            beneficiaryPaymentHistoryRepository.save(h);
                        });

                txn.setStatus(TransactionStatus.FAILED);
                transactionRepository.save(txn);
                transactionEventRepository.save(new TransactionEventEntity(
                        txnId, "BENE_NACK", timestamp, "PENDING", "FAILED", "Beneficiary declined transaction"
                ));
            } else if ("NO_RESPONSE".equalsIgnoreCase(responseCode)) {
                // Beneficiary bank timed out / no response -> retain PENDING status
                transactionEventRepository.save(new TransactionEventEntity(
                        txnId, "BENE_NO_RESPONSE", timestamp, "PENDING", "PENDING", "No response from beneficiary bank"
                ));
            } else {
                log.warn("Unknown response code {} received for txn {}", responseCode, txnId);
            }
        } else if (txn.getStatus() == TransactionStatus.REVERSED) {
            if ("ACK".equalsIgnoreCase(responseCode)) {
                // BR-06: Late ACK after auto-reversal
                VpaDetails payerVpaDetails = vpaService.getVpaOrThrow(txn.getPayerVpa());
                BigDecimal payerBal = accountService.getBalance(payerVpaDetails.accountId());

                if (payerVpaDetails.active() && payerBal.compareTo(txn.getAmount()) >= 0) {
                    // DEEMED_SUCCESS: Payer has balance to re-debit
                    AccountEntity payerAcc = accountService.debitAccount(payerVpaDetails.accountId(), txn.getAmount());
                    ledgerService.recordDebit(txnId, payerVpaDetails.accountId(), txn.getPayerVpa(),
                            txn.getAmount(), payerAcc.getBalance(), timestamp, "Re-debit on Late ACK for " + txnId);

                    VpaDetails payeeVpaDetails = vpaService.getVpaOrThrow(txn.getPayeeVpa());
                    AccountEntity payeeAcc = accountService.creditAccount(payeeVpaDetails.accountId(), txn.getAmount());
                    ledgerService.recordCredit(txnId, payeeVpaDetails.accountId(), txn.getPayeeVpa(),
                            txn.getAmount(), payeeAcc.getBalance(), timestamp, "Beneficiary credit on Late ACK for " + txnId);

                    // Re-increment counters
                    counterService.incrementDaily(payerVpaDetails.ownerId(), timestamp.toLocalDate(), txn.getAmount());
                    counterService.incrementMonthly(payerVpaDetails.ownerId(), timestamp.toLocalDate(), txn.getAmount());

                    // Re-increment beneficiary payment history
                    beneficiaryPaymentHistoryRepository.findByPayerVpaAndPayeeVpa(txn.getPayerVpa(), txn.getPayeeVpa())
                            .ifPresentOrElse(
                                    h -> {
                                        h.setPaymentCount(h.getPaymentCount() + 1);
                                        h.setLastPaymentAt(timestamp);
                                        beneficiaryPaymentHistoryRepository.save(h);
                                    },
                                    () -> beneficiaryPaymentHistoryRepository.save(new BeneficiaryPaymentHistoryEntity(
                                            txn.getPayerVpa(), txn.getPayeeVpa(), timestamp, 1, timestamp
                                    ))
                            );

                    txn.setStatus(TransactionStatus.DEEMED_SUCCESS);
                    txn.setDeemedSuccessAt(timestamp);
                    transactionRepository.save(txn);
                    transactionEventRepository.save(new TransactionEventEntity(
                            txnId, "LATE_ACK_DEEMED_SUCCESS", timestamp, "REVERSED", "DEEMED_SUCCESS",
                            "Deemed success after late ACK"
                    ));
                } else {
                    // RECON_SHORTFALL: Payer has spent funds or account inactive, overdraft forbidden!
                    txn.setStatus(TransactionStatus.RECON_SHORTFALL);
                    transactionRepository.save(txn);

                    BigDecimal shortfall = txn.getAmount().subtract(payerBal);
                    if (shortfall.compareTo(BigDecimal.ZERO) < 0) {
                        shortfall = txn.getAmount(); // Inactive account with higher balance still deficient in re-debit authorization
                    }
                    reconciliationRecordRepository.save(new ReconciliationRecordEntity(
                            txnId, "RECON_SHORTFALL", timestamp, txn.getAmount(),
                            txn.getPayerVpa(), txn.getPayeeVpa(), payerBal, "PENDING",
                            String.format("Payer available balance %s insufficient or VPA inactive to re-debit %s on late ACK. Exact shortfall amount: %s",
                                    payerBal, txn.getAmount(), shortfall)
                    ));

                    transactionEventRepository.save(new TransactionEventEntity(
                            txnId, "LATE_ACK_RECON_SHORTFALL", timestamp, "REVERSED", "RECON_SHORTFALL",
                            "Shortfall recorded due to insufficient balance or inactive VPA on late ACK"
                    ));
                }
            } else if ("NACK".equalsIgnoreCase(responseCode)) {
                log.info("Late NACK received for already reversed txn {}", txnId);
                transactionEventRepository.save(new TransactionEventEntity(
                        txnId, "LATE_BENE_NACK", timestamp, "REVERSED", "REVERSED",
                        "Late NACK received after auto-reversal; payer already refunded"
                ));
            } else {
                log.info("Late response {} received for reversed txn {}", responseCode, txnId);
            }
        } else if (txn.getStatus() == TransactionStatus.SUCCESS) {
            if ("ACK".equalsIgnoreCase(responseCode)) {
                log.warn("Duplicate ACK received for already SUCCESS transaction {}", txnId);
                transactionEventRepository.save(new TransactionEventEntity(
                        txnId, "DUPLICATE_BENE_ACK", timestamp, "SUCCESS", "SUCCESS", "Duplicate ACK ignored"
                ));
            } else if ("NACK".equalsIgnoreCase(responseCode)) {
                log.warn("Conflicting NACK received for already SUCCESS transaction {}", txnId);
                VpaDetails payerVpa = vpaService.getVpaOrThrow(txn.getPayerVpa());
                BigDecimal payerBal = accountService.getBalance(payerVpa.accountId());
                reconciliationRecordRepository.save(new ReconciliationRecordEntity(
                        txnId, "CONFLICTING_PACKET", timestamp, txn.getAmount(),
                        txn.getPayerVpa(), txn.getPayeeVpa(), payerBal, "PENDING",
                        "NACK received after transaction already reached terminal SUCCESS"
                ));
                transactionEventRepository.save(new TransactionEventEntity(
                        txnId, "CONFLICTING_BENE_NACK", timestamp, "SUCCESS", "SUCCESS", "Conflicting NACK ignored"
                ));
            }
        } else if (txn.getStatus() == TransactionStatus.FAILED) {
            if ("NACK".equalsIgnoreCase(responseCode)) {
                log.warn("Duplicate NACK received for already FAILED transaction {}", txnId);
                transactionEventRepository.save(new TransactionEventEntity(
                        txnId, "DUPLICATE_BENE_NACK", timestamp, "FAILED", "FAILED", "Duplicate NACK ignored"
                ));
            } else if ("ACK".equalsIgnoreCase(responseCode)) {
                log.warn("Conflicting ACK received for already FAILED transaction {}", txnId);
                VpaDetails payerVpa = vpaService.getVpaOrThrow(txn.getPayerVpa());
                BigDecimal payerBal = accountService.getBalance(payerVpa.accountId());
                reconciliationRecordRepository.save(new ReconciliationRecordEntity(
                        txnId, "CONFLICTING_PACKET", timestamp, txn.getAmount(),
                        txn.getPayerVpa(), txn.getPayeeVpa(), payerBal, "PENDING",
                        "ACK received after transaction already reached terminal FAILED"
                ));
                transactionEventRepository.save(new TransactionEventEntity(
                        txnId, "CONFLICTING_BENE_ACK", timestamp, "FAILED", "FAILED", "Conflicting ACK ignored"
                ));
            }
        } else if (txn.getStatus() == TransactionStatus.DEEMED_SUCCESS) {
            if ("ACK".equalsIgnoreCase(responseCode)) {
                log.warn("Duplicate ACK received for already DEEMED_SUCCESS transaction {}", txnId);
                transactionEventRepository.save(new TransactionEventEntity(
                        txnId, "DUPLICATE_LATE_ACK", timestamp, "DEEMED_SUCCESS", "DEEMED_SUCCESS",
                        "Duplicate late ACK ignored"
                ));
            }
        } else if (txn.getStatus() == TransactionStatus.RECON_SHORTFALL) {
            if ("ACK".equalsIgnoreCase(responseCode)) {
                log.warn("Duplicate ACK received for already RECON_SHORTFALL transaction {}", txnId);
                transactionEventRepository.save(new TransactionEventEntity(
                        txnId, "DUPLICATE_LATE_ACK_SHORTFALL", timestamp, "RECON_SHORTFALL", "RECON_SHORTFALL",
                        "Duplicate late ACK ignored; already RECON_SHORTFALL"
                ));
            }
        }
    }
}
