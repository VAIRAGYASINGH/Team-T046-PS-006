package com.ps006.upiswitch.collect.service;

import com.ps006.upiswitch.collect.entity.CollectRequestEntity;
import com.ps006.upiswitch.collect.model.CollectStatus;
import com.ps006.upiswitch.collect.repository.CollectRepository;
import com.ps006.upiswitch.common.util.RrnGenerator;
import com.ps006.upiswitch.payment.entity.TransactionEntity;
import com.ps006.upiswitch.payment.entity.TransactionEventEntity;
import com.ps006.upiswitch.payment.model.TransactionStatus;
import com.ps006.upiswitch.payment.model.TransactionType;
import com.ps006.upiswitch.payment.repository.TransactionEventRepository;
import com.ps006.upiswitch.payment.repository.TransactionRepository;
import com.ps006.upiswitch.reporting.model.TxnLogRow;
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
import java.util.Optional;

@Service
@Transactional
public class CollectService {

    private static final Logger log = LoggerFactory.getLogger(CollectService.class);
    private static final BigDecimal PIN_THRESHOLD = new BigDecimal("2000.00");

    private final CollectRepository collectRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionEventRepository transactionEventRepository;
    private final CollectExpiryService collectExpiryService;
    private final CollectApprovalService collectApprovalService;
    private final CollectDeclineService collectDeclineService;
    private final VpaService vpaService;

    public CollectService(CollectRepository collectRepository,
                          TransactionRepository transactionRepository,
                          TransactionEventRepository transactionEventRepository,
                          CollectExpiryService collectExpiryService,
                          CollectApprovalService collectApprovalService,
                          CollectDeclineService collectDeclineService,
                          VpaService vpaService) {
        this.collectRepository = collectRepository;
        this.transactionRepository = transactionRepository;
        this.transactionEventRepository = transactionEventRepository;
        this.collectExpiryService = collectExpiryService;
        this.collectApprovalService = collectApprovalService;
        this.collectDeclineService = collectDeclineService;
        this.vpaService = vpaService;
    }

    /**
     * Initiates a UPI collect request according to MASTER PROMPT 07.
     * Rule 1: Default expiry: 30 minutes.
     * Rule 2: Maximum expiry: 72 hours (4,320 minutes).
     * Rule 3: Amount strictly greater than ₹2,000 requires UPI PIN flag.
     * Rule 4: ₹2,000 exactly does not require PIN.
     */
    public TxnLogRow initiateCollect(int lineNo, String txnId, LocalDateTime timestamp,
                                     String requesterVpa, String payerVpa, BigDecimal amount,
                                     int expiryMinutes, boolean pinFlag) {
        String rrn = RrnGenerator.generateRrn();

        // 1. Amount validation
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Invalid non-positive collect amount: {}", amount);
            return new TxnLogRow(lineNo, txnId, "COLLECT", timestamp, payerVpa, requesterVpa,
                    amount != null ? amount : BigDecimal.ZERO, "FAILED", rrn, "INVALID_AMOUNT");
        }
        BigDecimal stdAmount = amount.setScale(2, RoundingMode.HALF_UP);

        // 2. VPA presence and format validation
        if (requesterVpa == null || requesterVpa.trim().isEmpty() || payerVpa == null || payerVpa.trim().isEmpty()) {
            log.warn("Missing or empty VPA in collect request: req={}, payer={}", requesterVpa, payerVpa);
            return new TxnLogRow(lineNo, txnId, "COLLECT", timestamp, payerVpa, requesterVpa, stdAmount, "FAILED", rrn, "INVALID_VPA");
        }

        // 3. Self-transfer validation
        if (requesterVpa.trim().equalsIgnoreCase(payerVpa.trim())) {
            log.warn("Self-collect requested: {}", requesterVpa);
            return new TxnLogRow(lineNo, txnId, "COLLECT", timestamp, payerVpa, requesterVpa, stdAmount, "FAILED", rrn, "SELF_TRANSFER_NOT_ALLOWED");
        }

        // 4. Duplicate check
        if (transactionRepository.findByTxnId(txnId).isPresent()) {
            log.warn("Duplicate txn_id detected on COLLECT: {}", txnId);
            return new TxnLogRow(lineNo, txnId, "COLLECT", timestamp, payerVpa, requesterVpa, stdAmount, "FAILED", rrn, "DUPLICATE_TXN_ID");
        }

        // 5. Expiry and PIN validation (BR-07)
        int resolvedExpiryMinutes = collectExpiryService.resolveExpiryMinutes(expiryMinutes);
        boolean pinRequired = stdAmount.compareTo(PIN_THRESHOLD) > 0;
        boolean pinInvalid = pinRequired && !pinFlag;
        boolean expiryInvalid = !collectExpiryService.isExpiryValid(resolvedExpiryMinutes);

        if (pinInvalid || expiryInvalid) {
            log.info("Collect request {} failed BR-07: pinInvalid={}, expiryInvalid={}", txnId, pinInvalid, expiryInvalid);
            TransactionEntity txn = new TransactionEntity(
                    txnId, rrn, "COLLECT", payerVpa, requesterVpa, stdAmount, "Collect Request",
                    TransactionType.P2P, TransactionStatus.FAILED, "BR-07", BigDecimal.ZERO,
                    timestamp, null, null
            );
            transactionRepository.save(txn);

            CollectRequestEntity collectRequest = new CollectRequestEntity(
                    txnId, requesterVpa, payerVpa, stdAmount, resolvedExpiryMinutes, pinFlag,
                    timestamp.plusMinutes(resolvedExpiryMinutes), CollectStatus.FAILED
            );
            collectRepository.save(collectRequest);

            return new TxnLogRow(lineNo, txnId, "COLLECT", timestamp, payerVpa, requesterVpa, stdAmount, "FAILED", rrn, "BR-07");
        }

        // 6. VPA existence checks
        Optional<VpaDetails> reqOpt = vpaService.findByVpa(requesterVpa);
        Optional<VpaDetails> payerOpt = vpaService.findByVpa(payerVpa);

        if (reqOpt.isEmpty() || payerOpt.isEmpty()) {
            String errorRule = reqOpt.isEmpty() ? "PAYEE_NOT_FOUND" : "PAYER_NOT_FOUND";
            log.warn("VPA resolution failed for collect {}: {}", txnId, errorRule);
            TransactionEntity txn = new TransactionEntity(
                    txnId, rrn, "COLLECT", payerVpa, requesterVpa, stdAmount, "Collect Request",
                    TransactionType.P2P, TransactionStatus.FAILED, errorRule, BigDecimal.ZERO,
                    timestamp, null, null
            );
            transactionRepository.save(txn);
            return new TxnLogRow(lineNo, txnId, "COLLECT", timestamp, payerVpa, requesterVpa, stdAmount, "FAILED", rrn, errorRule);
        }

        TransactionType txnType = reqOpt.get().ownerType() == OwnerType.MERCHANT
                ? TransactionType.P2M : TransactionType.P2P;

        LocalDateTime expiresAt = timestamp.plusMinutes(resolvedExpiryMinutes);
        TransactionEntity txn = new TransactionEntity(
                txnId, rrn, "COLLECT", payerVpa, requesterVpa, stdAmount, "Collect Request",
                txnType, TransactionStatus.PENDING, null, BigDecimal.ZERO,
                timestamp, null, null
        );
        transactionRepository.save(txn);

        CollectRequestEntity collectRequest = new CollectRequestEntity(
                txnId, requesterVpa, payerVpa, stdAmount, resolvedExpiryMinutes, pinFlag, expiresAt, CollectStatus.PENDING
        );
        collectRepository.save(collectRequest);

        transactionEventRepository.save(new TransactionEventEntity(
                txnId, "COLLECT_INITIATED", timestamp, null, "PENDING", "Collect request created"
        ));

        return new TxnLogRow(lineNo, txnId, "COLLECT", timestamp, payerVpa, requesterVpa, stdAmount, "PENDING", rrn, "");
    }

    public TxnLogRow approveCollect(int lineNo, String txnId, LocalDateTime timestamp) {
        return collectApprovalService.approveCollect(lineNo, txnId, timestamp);
    }

    public TxnLogRow declineCollect(int lineNo, String txnId, LocalDateTime timestamp) {
        return collectDeclineService.declineCollect(lineNo, txnId, timestamp);
    }
}
