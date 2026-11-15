package com.ps006.upiswitch.reversal.service;

import com.ps006.upiswitch.account.entity.AccountEntity;
import com.ps006.upiswitch.account.service.AccountService;
import com.ps006.upiswitch.common.util.RrnGenerator;
import com.ps006.upiswitch.ledger.model.LegType;
import com.ps006.upiswitch.ledger.service.LedgerService;
import com.ps006.upiswitch.payment.entity.TransactionEntity;
import com.ps006.upiswitch.payment.entity.TransactionEventEntity;
import com.ps006.upiswitch.payment.model.TransactionStatus;
import com.ps006.upiswitch.payment.repository.TransactionEventRepository;
import com.ps006.upiswitch.payment.repository.TransactionRepository;
import com.ps006.upiswitch.reporting.model.TxnLogRow;
import com.ps006.upiswitch.reversal.entity.RefundEntity;
import com.ps006.upiswitch.reversal.repository.RefundRepository;
import com.ps006.upiswitch.vpa.model.VpaDetails;
import com.ps006.upiswitch.vpa.service.VpaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@Service
@Transactional
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final RefundRepository refundRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionEventRepository transactionEventRepository;
    private final VpaService vpaService;
    private final AccountService accountService;
    private final LedgerService ledgerService;

    public RefundService(RefundRepository refundRepository,
                         TransactionRepository transactionRepository,
                         TransactionEventRepository transactionEventRepository,
                         VpaService vpaService,
                         AccountService accountService,
                         LedgerService ledgerService) {
        this.refundRepository = refundRepository;
        this.transactionRepository = transactionRepository;
        this.transactionEventRepository = transactionEventRepository;
        this.vpaService = vpaService;
        this.accountService = accountService;
        this.ledgerService = ledgerService;
    }

    public TxnLogRow processRefund(int lineNo, String originalTxnId, LocalDateTime timestamp, BigDecimal amount) {
        String rrn = RrnGenerator.generateRrn();
        BigDecimal stdAmount = amount.setScale(2, RoundingMode.UNNECESSARY);

        TransactionEntity origTxn = transactionRepository.findByTxnIdForUpdate(originalTxnId)
                .orElse(null);

        if (origTxn == null || (origTxn.getStatus() != TransactionStatus.SUCCESS && origTxn.getStatus() != TransactionStatus.DEEMED_SUCCESS)) {
            log.warn("Refund rejected: original txn {} not eligible", originalTxnId);
            return new TxnLogRow(lineNo, originalTxnId, "REFUND", timestamp, "UNKNOWN", "UNKNOWN", stdAmount, "FAILED", rrn, "INVALID_ORIG_TXN");
        }

        BigDecimal eligibleRefund = origTxn.getAmount().subtract(origTxn.getRefundedAmount());
        if (stdAmount.compareTo(eligibleRefund) > 0) {
            log.warn("Refund amount {} exceeds eligible {} for txn {}", stdAmount, eligibleRefund, originalTxnId);
            return new TxnLogRow(lineNo, originalTxnId, "REFUND", timestamp, origTxn.getPayeeVpa(), origTxn.getPayerVpa(), stdAmount, "FAILED", rrn, "REFUND_EXCEEDS_AMOUNT");
        }

        // Debit merchant (payee of original txn)
        VpaDetails merchantVpa = vpaService.getVpaOrThrow(origTxn.getPayeeVpa());
        AccountEntity merchantAcc = accountService.debitAccount(merchantVpa.accountId(), stdAmount);
        ledgerService.recordEntry(originalTxnId, merchantVpa.accountId(), origTxn.getPayeeVpa(),
                LegType.REFUND, stdAmount, null, merchantAcc.getBalance(), timestamp, null, "Refund to " + origTxn.getPayerVpa());

        // Credit customer (payer of original txn)
        VpaDetails customerVpa = vpaService.getVpaOrThrow(origTxn.getPayerVpa());
        AccountEntity customerAcc = accountService.creditAccount(customerVpa.accountId(), stdAmount);
        ledgerService.recordEntry(originalTxnId, customerVpa.accountId(), origTxn.getPayerVpa(),
                LegType.REFUND, null, stdAmount, customerAcc.getBalance(), timestamp, null, "Refund from " + origTxn.getPayeeVpa());

        // Update original transaction
        origTxn.setRefundedAmount(origTxn.getRefundedAmount().add(stdAmount));
        transactionRepository.save(origTxn);

        // Record refund entity
        RefundEntity refundEntity = new RefundEntity(
                originalTxnId, timestamp, stdAmount, origTxn.getPayeeVpa(), origTxn.getPayerVpa(), "SUCCESS", rrn
        );
        refundRepository.save(refundEntity);

        transactionEventRepository.save(new TransactionEventEntity(
                originalTxnId, "REFUND_SUCCESS", timestamp, origTxn.getStatus().name(), origTxn.getStatus().name(),
                "Refund processed for " + stdAmount
        ));

        // Note: For REFUND row in txn_log.csv, per contract section 3.2:
        // payer_vpa = remitter VPA (or original payer for refund), payee_vpa = beneficiary (or original payee)
        // Check section 3.3 example:
        // line 73: 73,TXN-0027,REFUND,2026-10-17 10:00:00,udupi@icici,anjali374@paytm,300.00,SUCCESS,000000000030,
        // Notice in line 73: payer_vpa column is udupi@icici (the refund source/merchant), payee_vpa is anjali374@paytm (customer)!
        return new TxnLogRow(lineNo, originalTxnId, "REFUND", timestamp, origTxn.getPayeeVpa(), origTxn.getPayerVpa(), stdAmount, "SUCCESS", rrn, "");
    }
}

