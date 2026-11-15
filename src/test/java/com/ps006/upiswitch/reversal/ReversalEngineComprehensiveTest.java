package com.ps006.upiswitch.reversal;

import com.ps006.upiswitch.account.entity.AccountEntity;
import com.ps006.upiswitch.account.service.AccountService;
import com.ps006.upiswitch.common.entity.ReconciliationRecordEntity;
import com.ps006.upiswitch.common.repository.ReconciliationRecordRepository;
import com.ps006.upiswitch.config.SwitchProperties;
import com.ps006.upiswitch.counter.service.CounterService;
import com.ps006.upiswitch.ledger.model.LegType;
import com.ps006.upiswitch.ledger.service.LedgerService;
import com.ps006.upiswitch.merchant.service.MerchantService;
import com.ps006.upiswitch.payment.entity.BeneficiaryPaymentHistoryEntity;
import com.ps006.upiswitch.payment.entity.TransactionEntity;
import com.ps006.upiswitch.payment.entity.TransactionEventEntity;
import com.ps006.upiswitch.payment.model.TransactionStatus;
import com.ps006.upiswitch.payment.model.TransactionType;
import com.ps006.upiswitch.payment.repository.BeneficiaryPaymentHistoryRepository;
import com.ps006.upiswitch.payment.repository.TransactionEventRepository;
import com.ps006.upiswitch.payment.repository.TransactionRepository;
import com.ps006.upiswitch.payment.service.PaymentService;
import com.ps006.upiswitch.reversal.service.TimeoutJobService;
import com.ps006.upiswitch.user.service.UserService;
import com.ps006.upiswitch.validation.service.ValidationPipelineService;
import com.ps006.upiswitch.vpa.model.OwnerType;
import com.ps006.upiswitch.vpa.model.VpaDetails;
import com.ps006.upiswitch.vpa.service.VpaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 6 Comprehensive Test Suite for Transaction Lifecycle and Reversal Engine.
 * Verifies ACK, NACK, NO_RESPONSE, Timeout auto-reversal, Late ACK (DEEMED_SUCCESS vs RECON_SHORTFALL),
 * exact-once counter restoration, and concurrency / idempotency invariants.
 */
class ReversalEngineComprehensiveTest {

    private TransactionRepository transactionRepository;
    private TransactionEventRepository transactionEventRepository;
    private BeneficiaryPaymentHistoryRepository beneficiaryPaymentHistoryRepository;
    private ReconciliationRecordRepository reconciliationRecordRepository;
    private VpaService vpaService;
    private AccountService accountService;
    private UserService userService;
    private MerchantService merchantService;
    private CounterService counterService;
    private LedgerService ledgerService;
    private ValidationPipelineService validationPipelineService;
    private SwitchProperties switchProperties;

    private PaymentService paymentService;
    private TimeoutJobService timeoutJobService;

    private final LocalDateTime initiatedAt = LocalDateTime.of(2026, 10, 8, 10, 0, 0);
    private final LocalDate txnDate = initiatedAt.toLocalDate();

    private final VpaDetails payerVpa = new VpaDetails("rohit@okaxis", 1L, OwnerType.USER, "USR-001", initiatedAt.minusMonths(2), true);
    private final VpaDetails payeeVpa = new VpaDetails("aditya@oksbi", 2L, OwnerType.USER, "USR-002", initiatedAt.minusMonths(3), true);

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        transactionEventRepository = mock(TransactionEventRepository.class);
        beneficiaryPaymentHistoryRepository = mock(BeneficiaryPaymentHistoryRepository.class);
        reconciliationRecordRepository = mock(ReconciliationRecordRepository.class);
        vpaService = mock(VpaService.class);
        accountService = mock(AccountService.class);
        userService = mock(UserService.class);
        merchantService = mock(MerchantService.class);
        counterService = mock(CounterService.class);
        ledgerService = mock(LedgerService.class);
        validationPipelineService = mock(ValidationPipelineService.class);

        switchProperties = new SwitchProperties();
        switchProperties.getTimeout().setPendingPaymentTimeoutMinutes(30L);

        paymentService = new PaymentService(
                transactionRepository,
                transactionEventRepository,
                beneficiaryPaymentHistoryRepository,
                reconciliationRecordRepository,
                vpaService,
                accountService,
                userService,
                merchantService,
                counterService,
                ledgerService,
                validationPipelineService
        );

        timeoutJobService = new TimeoutJobService(
                transactionRepository,
                transactionEventRepository,
                beneficiaryPaymentHistoryRepository,
                accountService,
                vpaService,
                counterService,
                ledgerService,
                switchProperties
        );

        when(vpaService.getVpaOrThrow("rohit@okaxis")).thenReturn(payerVpa);
        when(vpaService.getVpaOrThrow("aditya@oksbi")).thenReturn(payeeVpa);
        when(vpaService.findByVpa("rohit@okaxis")).thenReturn(Optional.of(payerVpa));
        when(vpaService.findByVpa("aditya@oksbi")).thenReturn(Optional.of(payeeVpa));
    }

    // =========================================================================
    // 1. ACK BEFORE TIMEOUT
    // =========================================================================

    @Test
    @DisplayName("1. ACK before timeout: credits beneficiary, sets SUCCESS, and subsequent timeout job skips")
    void testAckBeforeTimeout() {
        String txnId = "TXN-0001";
        BigDecimal amount = new BigDecimal("8000.00");
        TransactionEntity txn = new TransactionEntity(
                txnId, "000000000001", "PAY", "rohit@okaxis", "aditya@oksbi",
                amount, "Payment", TransactionType.P2P,
                TransactionStatus.PENDING, null, BigDecimal.ZERO, initiatedAt, null, null
        );

        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(txn));
        AccountEntity payeeAcc = new AccountEntity(2L, "100000000002", "SBIN001", "USR-002", null,
                new BigDecimal("128000.00"), "INR", 0L, initiatedAt, initiatedAt);
        when(accountService.creditAccount(2L, amount)).thenReturn(payeeAcc);

        // ACK arrives at 10:05:00 (5 minutes after initiation)
        LocalDateTime ackTime = initiatedAt.plusMinutes(5);
        paymentService.handleBeneficiaryResponse(txnId, ackTime, "ACK");

        // Assertions for ACK
        assertEquals(TransactionStatus.SUCCESS, txn.getStatus());
        verify(accountService).creditAccount(2L, amount);
        verify(ledgerService).recordCredit(eq(txnId), eq(2L), eq("aditya@oksbi"), eq(amount),
                eq(new BigDecimal("128000.00")), eq(ackTime), anyString());

        // Subsequent timeout job runs at 10:30:00
        LocalDateTime timeoutTime = initiatedAt.plusMinutes(30);
        when(transactionRepository.findByStatusAndInitiatedAtBefore(eq(TransactionStatus.PENDING), any()))
                .thenReturn(Collections.emptyList());

        int reversed = timeoutJobService.processTimeouts(timeoutTime);

        // Assertions for timeout job
        assertEquals(0, reversed, "Timeout job must skip non-PENDING transactions");
        verify(accountService, never()).creditAccount(eq(1L), any()); // Remitter not refunded
        assertEquals(TransactionStatus.SUCCESS, txn.getStatus());
    }

    // =========================================================================
    // 2. NACK BEFORE TIMEOUT
    // =========================================================================

    @Test
    @DisplayName("2. NACK before timeout: refunds remitter, posts REVERSAL ledger, restores counters, sets FAILED")
    void testNackBeforeTimeout() {
        String txnId = "TXN-0033";
        BigDecimal amount = new BigDecimal("500.00");
        TransactionEntity txn = new TransactionEntity(
                txnId, "000000000033", "PAY", "rohit@okaxis", "aditya@oksbi",
                amount, "Failed pay", TransactionType.P2P,
                TransactionStatus.PENDING, null, BigDecimal.ZERO, initiatedAt, null, null
        );

        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(txn));
        AccountEntity payerAcc = new AccountEntity(1L, "100000000001", "UTIB001", "USR-001", null,
                new BigDecimal("50000.00"), "INR", 0L, initiatedAt, initiatedAt);
        when(accountService.creditAccount(1L, amount)).thenReturn(payerAcc);

        BeneficiaryPaymentHistoryEntity history = new BeneficiaryPaymentHistoryEntity(
                "rohit@okaxis", "aditya@oksbi", initiatedAt, 1, initiatedAt
        );
        when(beneficiaryPaymentHistoryRepository.findByPayerVpaAndPayeeVpa("rohit@okaxis", "aditya@oksbi"))
                .thenReturn(Optional.of(history));

        // NACK arrives at 10:02:00
        LocalDateTime nackTime = initiatedAt.plusMinutes(2);
        paymentService.handleBeneficiaryResponse(txnId, nackTime, "NACK");

        // Verify remitter refund
        verify(accountService).creditAccount(1L, amount);
        verify(ledgerService).recordEntry(eq(txnId), eq(1L), eq("rohit@okaxis"), eq(LegType.REVERSAL),
                isNull(), eq(amount), eq(new BigDecimal("50000.00")), eq(nackTime), isNull(), anyString());

        // Verify counters restored
        verify(counterService).decrementDaily("USR-001", txnDate, amount);
        verify(counterService).decrementMonthly("USR-001", txnDate, amount);
        assertEquals(0, history.getPaymentCount(), "Beneficiary history count must be decremented");

        // Verify transaction marked FAILED
        assertEquals(TransactionStatus.FAILED, txn.getStatus());

        // Subsequent timeout job skips
        LocalDateTime timeoutTime = initiatedAt.plusMinutes(30);
        int reversed = timeoutJobService.processTimeouts(timeoutTime);
        assertEquals(0, reversed);
    }

    // =========================================================================
    // 3. TIMEOUT AT EXACTLY 30 MINUTES
    // =========================================================================

    @Test
    @DisplayName("3. Timeout at exactly 30 minutes (1,800s): locks row, refunds remitter, restores counters, sets REVERSED")
    void testTimeoutAtExactly30Minutes() {
        String txnId = "TXN-0015";
        BigDecimal amount = new BigDecimal("12000.00");
        TransactionEntity txn = new TransactionEntity(
                txnId, "000000000015", "PAY", "rohit@okaxis", "aditya@oksbi",
                amount, "Loan", TransactionType.P2P,
                TransactionStatus.PENDING, null, BigDecimal.ZERO, initiatedAt, null, null
        );

        LocalDateTime timeoutTime = initiatedAt.plusMinutes(30); // Exactly 10:30:00

        when(transactionRepository.findByStatusAndInitiatedAtBefore(TransactionStatus.PENDING, initiatedAt))
                .thenReturn(List.of(txn));
        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(txn));

        AccountEntity payerAcc = new AccountEntity(1L, "100000000001", "UTIB001", "USR-001", null,
                new BigDecimal("50000.00"), "INR", 0L, timeoutTime, timeoutTime);
        when(accountService.creditAccount(1L, amount)).thenReturn(payerAcc);

        BeneficiaryPaymentHistoryEntity history = new BeneficiaryPaymentHistoryEntity(
                "rohit@okaxis", "aditya@oksbi", initiatedAt, 1, initiatedAt
        );
        when(beneficiaryPaymentHistoryRepository.findByPayerVpaAndPayeeVpaForUpdate("rohit@okaxis", "aditya@oksbi"))
                .thenReturn(Optional.of(history));

        int reversed = timeoutJobService.processTimeouts(timeoutTime);

        // Verification
        assertEquals(1, reversed, "Transaction at exactly 30m boundary must be auto-reversed");
        assertEquals(TransactionStatus.REVERSED, txn.getStatus());
        assertEquals(timeoutTime, txn.getReversedAt(), "ReversedAt must match timeout job timestamp");

        verify(accountService).creditAccount(1L, amount);
        verify(ledgerService).recordEntry(eq(txnId), eq(1L), eq("rohit@okaxis"), eq(LegType.REVERSAL),
                isNull(), eq(amount), eq(new BigDecimal("50000.00")), eq(timeoutTime), isNull(), anyString());

        verify(counterService).decrementDaily("USR-001", txnDate, amount);
        verify(counterService).decrementMonthly("USR-001", txnDate, amount);
        assertEquals(0, history.getPaymentCount());
    }

    // =========================================================================
    // 4. ACK 1 SECOND BEFORE TIMEOUT
    // =========================================================================

    @Test
    @DisplayName("4. ACK 1 second before timeout (1,799s): transitions to SUCCESS; timeout job at 1,800s skips")
    void testAckOneSecondBeforeTimeout() {
        String txnId = "TXN-0015";
        BigDecimal amount = new BigDecimal("12000.00");
        TransactionEntity txn = new TransactionEntity(
                txnId, "000000000015", "PAY", "rohit@okaxis", "aditya@oksbi",
                amount, "Loan", TransactionType.P2P,
                TransactionStatus.PENDING, null, BigDecimal.ZERO, initiatedAt, null, null
        );

        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(txn));
        AccountEntity payeeAcc = new AccountEntity(2L, "100000000002", "SBIN001", "USR-002", null,
                new BigDecimal("132000.00"), "INR", 0L, initiatedAt, initiatedAt);
        when(accountService.creditAccount(2L, amount)).thenReturn(payeeAcc);

        // ACK at 10:29:59 (1,799 seconds)
        LocalDateTime ackTime = initiatedAt.plusSeconds(1799);
        paymentService.handleBeneficiaryResponse(txnId, ackTime, "ACK");

        assertEquals(TransactionStatus.SUCCESS, txn.getStatus());

        // Timeout job executes 1 second later at 10:30:00
        LocalDateTime timeoutTime = initiatedAt.plusSeconds(1800);
        when(transactionRepository.findByStatusAndInitiatedAtBefore(TransactionStatus.PENDING, initiatedAt))
                .thenReturn(Collections.emptyList());

        int reversed = timeoutJobService.processTimeouts(timeoutTime);

        assertEquals(0, reversed);
        assertEquals(TransactionStatus.SUCCESS, txn.getStatus());
        verify(accountService, never()).creditAccount(eq(1L), any());
    }

    // =========================================================================
    // 5. TIMEOUT FOLLOWED BY ACK 1 SECOND AFTER TIMEOUT
    // =========================================================================

    @Test
    @DisplayName("5. ACK 1 second after timeout (1,801s): auto-reversed at 1,800s, then re-debited as DEEMED_SUCCESS")
    void testAckOneSecondAfterTimeout() {
        String txnId = "TXN-0015";
        BigDecimal amount = new BigDecimal("12000.00");
        TransactionEntity txn = new TransactionEntity(
                txnId, "000000000015", "PAY", "rohit@okaxis", "aditya@oksbi",
                amount, "Loan", TransactionType.P2P,
                TransactionStatus.PENDING, null, BigDecimal.ZERO, initiatedAt, null, null
        );

        LocalDateTime timeoutTime = initiatedAt.plusSeconds(1800); // 10:30:00

        when(transactionRepository.findByStatusAndInitiatedAtBefore(TransactionStatus.PENDING, initiatedAt))
                .thenReturn(List.of(txn));
        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(txn));

        AccountEntity refundedAcc = new AccountEntity(1L, "100000000001", "UTIB001", "USR-001", null,
                new BigDecimal("50000.00"), "INR", 0L, timeoutTime, timeoutTime);
        when(accountService.creditAccount(1L, amount)).thenReturn(refundedAcc);

        // 1. Timeout job runs at 10:30:00
        int reversed = timeoutJobService.processTimeouts(timeoutTime);
        assertEquals(1, reversed);
        assertEquals(TransactionStatus.REVERSED, txn.getStatus());

        // 2. Late ACK arrives at 10:30:01 (1 second after timeout)
        LocalDateTime lateAckTime = initiatedAt.plusSeconds(1801);
        when(accountService.getBalance(1L)).thenReturn(new BigDecimal("50000.00")); // Payer has sufficient balance

        AccountEntity reDebitedAcc = new AccountEntity(1L, "100000000001", "UTIB001", "USR-001", null,
                new BigDecimal("38000.00"), "INR", 0L, lateAckTime, lateAckTime);
        when(accountService.debitAccount(1L, amount)).thenReturn(reDebitedAcc);

        AccountEntity payeeCreditedAcc = new AccountEntity(2L, "100000000002", "SBIN001", "USR-002", null,
                new BigDecimal("132000.00"), "INR", 0L, lateAckTime, lateAckTime);
        when(accountService.creditAccount(2L, amount)).thenReturn(payeeCreditedAcc);

        paymentService.handleBeneficiaryResponse(txnId, lateAckTime, "ACK");

        // Assertions for DEEMED_SUCCESS
        assertEquals(TransactionStatus.DEEMED_SUCCESS, txn.getStatus());
        assertEquals(timeoutTime, txn.getReversedAt(), "Preserves initial reversedAt timestamp");
        assertEquals(lateAckTime, txn.getDeemedSuccessAt(), "Records late ACK deemedSuccessAt timestamp");

        verify(accountService).debitAccount(1L, amount);
        verify(accountService).creditAccount(2L, amount);
        verify(counterService).incrementDaily("USR-001", txnDate, amount);
        verify(counterService).incrementMonthly("USR-001", txnDate, amount);
    }

    // =========================================================================
    // 6. LATE ACK: DEEMED_SUCCESS VS RECON_SHORTFALL
    // =========================================================================

    @Test
    @DisplayName("6A. Late ACK with sufficient balance -> DEEMED_SUCCESS: re-debit, credit payee, counters incremented")
    void testLateAckDeemedSuccessWithSufficientBalance() {
        String txnId = "TXN-0015";
        BigDecimal amount = new BigDecimal("12000.00");
        LocalDateTime reversedAt = initiatedAt.plusMinutes(30);

        TransactionEntity reversedTxn = new TransactionEntity(
                txnId, "000000000015", "PAY", "rohit@okaxis", "aditya@oksbi",
                amount, "Loan", TransactionType.P2P,
                TransactionStatus.REVERSED, null, BigDecimal.ZERO, initiatedAt, reversedAt, null
        );

        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(reversedTxn));
        when(accountService.getBalance(1L)).thenReturn(new BigDecimal("50000.00")); // > 12,000

        AccountEntity reDebitedAcc = new AccountEntity(1L, "100000000001", "UTIB001", "USR-001", null,
                new BigDecimal("38000.00"), "INR", 0L, reversedAt, reversedAt);
        when(accountService.debitAccount(1L, amount)).thenReturn(reDebitedAcc);

        AccountEntity payeeAcc = new AccountEntity(2L, "100000000002", "SBIN001", "USR-002", null,
                new BigDecimal("132000.00"), "INR", 0L, reversedAt, reversedAt);
        when(accountService.creditAccount(2L, amount)).thenReturn(payeeAcc);

        LocalDateTime lateAckTime = reversedAt.plusSeconds(40); // 10:30:40
        paymentService.handleBeneficiaryResponse(txnId, lateAckTime, "ACK");

        assertEquals(TransactionStatus.DEEMED_SUCCESS, reversedTxn.getStatus());
        assertEquals(reversedAt, reversedTxn.getReversedAt());
        assertEquals(lateAckTime, reversedTxn.getDeemedSuccessAt());

        // Verify ledger entries
        verify(ledgerService).recordDebit(eq(txnId), eq(1L), eq("rohit@okaxis"), eq(amount),
                eq(new BigDecimal("38000.00")), eq(lateAckTime), anyString());
        verify(ledgerService).recordCredit(eq(txnId), eq(2L), eq("aditya@oksbi"), eq(amount),
                eq(new BigDecimal("132000.00")), eq(lateAckTime), anyString());

        // Verify counter re-increment
        verify(counterService).incrementDaily("USR-001", txnDate, amount);
        verify(counterService).incrementMonthly("USR-001", txnDate, amount);

        // Verify no shortfall record
        verify(reconciliationRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("6B. Late ACK with insufficient balance -> RECON_SHORTFALL: zero overdraft, no ledger movement, shortfall logged")
    void testLateAckReconShortfallWithInsufficientBalance() {
        String txnId = "TXN-0017";
        BigDecimal amount = new BigDecimal("1400.00");
        LocalDateTime reversedAt = initiatedAt.plusMinutes(30);

        TransactionEntity reversedTxn = new TransactionEntity(
                txnId, "000000000017", "PAY", "rohit@okaxis", "aditya@oksbi",
                amount, "Rent", TransactionType.P2P,
                TransactionStatus.REVERSED, null, BigDecimal.ZERO, initiatedAt, reversedAt, null
        );

        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(reversedTxn));
        when(accountService.getBalance(1L)).thenReturn(new BigDecimal("300.00")); // Only 300 < 1400

        LocalDateTime lateAckTime = initiatedAt.plusHours(2); // 12:00:00
        paymentService.handleBeneficiaryResponse(txnId, lateAckTime, "ACK");

        // Verify status marked RECON_SHORTFALL
        assertEquals(TransactionStatus.RECON_SHORTFALL, reversedTxn.getStatus());
        assertEquals(reversedAt, reversedTxn.getReversedAt());
        assertNull(reversedTxn.getDeemedSuccessAt(), "deemedSuccessAt must remain null for RECON_SHORTFALL");

        // Verify ZERO account debits or credits (anti-overdraft invariant)
        verify(accountService, never()).debitAccount(anyLong(), any());
        verify(accountService, never()).creditAccount(anyLong(), any());
        verify(ledgerService, never()).recordDebit(any(), any(), any(), any(), any(), any(), any());
        verify(ledgerService, never()).recordCredit(any(), any(), any(), any(), any(), any(), any());
        verify(counterService, never()).incrementDaily(any(), any(), any());
        verify(counterService, never()).incrementMonthly(any(), any(), any());

        // Verify shortfall recorded in reconciliation_records
        ArgumentCaptor<ReconciliationRecordEntity> reconCaptor = ArgumentCaptor.forClass(ReconciliationRecordEntity.class);
        verify(reconciliationRecordRepository).save(reconCaptor.capture());
        ReconciliationRecordEntity recon = reconCaptor.getValue();

        assertEquals(txnId, recon.getTxnId());
        assertEquals("RECON_SHORTFALL", recon.getIncidentType());
        assertEquals(amount, recon.getAmount());
        assertEquals(new BigDecimal("300.00"), recon.getPayerAvailableBalance());
        assertTrue(recon.getDetails().contains("Exact shortfall amount: 1100.00"),
                "Shortfall details must preserve exact deficiency amount");
    }

    // =========================================================================
    // 7. DUPLICATE ACK AND NACK IDEMPOTENCY
    // =========================================================================

    @Test
    @DisplayName("7. Duplicate ACK: ignored idempotently without second credit or ledger entry")
    void testDuplicateAckHandling() {
        String txnId = "TXN-0001";
        TransactionEntity successTxn = new TransactionEntity(
                txnId, "000000000001", "PAY", "rohit@okaxis", "aditya@oksbi",
                new BigDecimal("8000.00"), "Payment", TransactionType.P2P,
                TransactionStatus.SUCCESS, null, BigDecimal.ZERO, initiatedAt, null, null
        );

        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(successTxn));

        // Duplicate ACK arrives 10 seconds later
        LocalDateTime dupAckTime = initiatedAt.plusMinutes(5).plusSeconds(10);
        paymentService.handleBeneficiaryResponse(txnId, dupAckTime, "ACK");

        // Status remains SUCCESS
        assertEquals(TransactionStatus.SUCCESS, successTxn.getStatus());

        // Zero additional balance or ledger changes
        verify(accountService, never()).creditAccount(anyLong(), any());
        verify(accountService, never()).debitAccount(anyLong(), any());
        verify(ledgerService, never()).recordCredit(any(), any(), any(), any(), any(), any(), any());

        // Verify audit event
        verify(transactionEventRepository).save(argThat(e ->
                "DUPLICATE_BENE_ACK".equals(e.getEventType()) && txnId.equals(e.getTxnId())
        ));
    }

    @Test
    @DisplayName("8. Duplicate NACK: ignored idempotently without second refund or double counter restoration")
    void testDuplicateNackHandling() {
        String txnId = "TXN-0033";
        TransactionEntity failedTxn = new TransactionEntity(
                txnId, "000000000033", "PAY", "rohit@okaxis", "aditya@oksbi",
                new BigDecimal("500.00"), "Failed", TransactionType.P2P,
                TransactionStatus.FAILED, null, BigDecimal.ZERO, initiatedAt, null, null
        );

        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(failedTxn));

        LocalDateTime dupNackTime = initiatedAt.plusMinutes(3);
        paymentService.handleBeneficiaryResponse(txnId, dupNackTime, "NACK");

        assertEquals(TransactionStatus.FAILED, failedTxn.getStatus());
        verify(accountService, never()).creditAccount(anyLong(), any());
        verify(counterService, never()).decrementDaily(any(), any(), any());
        verify(counterService, never()).decrementMonthly(any(), any(), any());

        verify(transactionEventRepository).save(argThat(e ->
                "DUPLICATE_BENE_NACK".equals(e.getEventType()) && txnId.equals(e.getTxnId())
        ));
    }

    // =========================================================================
    // 9. DUPLICATE TIMEOUT JOB IDEMPOTENCY
    // =========================================================================

    @Test
    @DisplayName("9. Duplicate Timeout Job: second sweep skips already REVERSED transactions; exactly 1 refund")
    void testDuplicateTimeoutJobIdempotency() {
        String txnId = "TXN-0015";
        BigDecimal amount = new BigDecimal("12000.00");
        TransactionEntity txn = new TransactionEntity(
                txnId, "000000000015", "PAY", "rohit@okaxis", "aditya@oksbi",
                amount, "Loan", TransactionType.P2P,
                TransactionStatus.PENDING, null, BigDecimal.ZERO, initiatedAt, null, null
        );

        LocalDateTime timeoutTime = initiatedAt.plusMinutes(30);

        when(transactionRepository.findByStatusAndInitiatedAtBefore(TransactionStatus.PENDING, initiatedAt))
                .thenReturn(List.of(txn))
                .thenReturn(Collections.emptyList()); // Second run returns empty list
        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(txn));

        AccountEntity payerAcc = new AccountEntity(1L, "100000000001", "UTIB001", "USR-001", null,
                new BigDecimal("50000.00"), "INR", 0L, timeoutTime, timeoutTime);
        when(accountService.creditAccount(1L, amount)).thenReturn(payerAcc);

        // Sweep 1: auto-reversal succeeds
        int reversedSweep1 = timeoutJobService.processTimeouts(timeoutTime);
        assertEquals(1, reversedSweep1);
        assertEquals(TransactionStatus.REVERSED, txn.getStatus());

        // Sweep 2: executed 5 minutes later at 10:35:00
        LocalDateTime secondSweepTime = initiatedAt.plusMinutes(35);
        int reversedSweep2 = timeoutJobService.processTimeouts(secondSweepTime);
        assertEquals(0, reversedSweep2);

        // Verify refund and counter restoration occurred EXACTLY ONCE
        verify(accountService, times(1)).creditAccount(1L, amount);
        verify(ledgerService, times(1)).recordEntry(any(), any(), any(), eq(LegType.REVERSAL), any(), any(), any(), any(), any(), any());
        verify(counterService, times(1)).decrementDaily("USR-001", txnDate, amount);
        verify(counterService, times(1)).decrementMonthly("USR-001", txnDate, amount);
    }

    // =========================================================================
    // 10. EXACT-ONCE COUNTER RESTORATION INVARIANT
    // =========================================================================

    @Test
    @DisplayName("10. Counter restoration occurs exactly once across NACK, Timeout, and duplicate calls")
    void testCounterRestorationOccursExactlyOnce() {
        String txnId = "TXN-0015";
        BigDecimal amount = new BigDecimal("12000.00");
        TransactionEntity txn = new TransactionEntity(
                txnId, "000000000015", "PAY", "rohit@okaxis", "aditya@oksbi",
                amount, "Loan", TransactionType.P2P,
                TransactionStatus.PENDING, null, BigDecimal.ZERO, initiatedAt, null, null
        );

        LocalDateTime timeoutTime = initiatedAt.plusMinutes(30);

        when(transactionRepository.findByStatusAndInitiatedAtBefore(TransactionStatus.PENDING, initiatedAt))
                .thenReturn(List.of(txn));
        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(txn));

        AccountEntity payerAcc = new AccountEntity(1L, "100000000001", "UTIB001", "USR-001", null,
                new BigDecimal("50000.00"), "INR", 0L, timeoutTime, timeoutTime);
        when(accountService.creditAccount(1L, amount)).thenReturn(payerAcc);

        timeoutJobService.processTimeouts(timeoutTime);

        // Counter decremented exactly once
        verify(counterService, times(1)).decrementDaily("USR-001", txnDate, amount);
        verify(counterService, times(1)).decrementMonthly("USR-001", txnDate, amount);

        // Now a duplicate NACK arrives after the reversal
        paymentService.handleBeneficiaryResponse(txnId, timeoutTime.plusMinutes(1), "NACK");

        // Counters MUST NOT be decremented a second time!
        verify(counterService, times(1)).decrementDaily("USR-001", txnDate, amount);
        verify(counterService, times(1)).decrementMonthly("USR-001", txnDate, amount);
    }

    // =========================================================================
    // 11. TIMEOUT SKIPPED IF UNDER 30 MINUTES
    // =========================================================================

    @Test
    @DisplayName("11. Timeout skipped if duration < 30 minutes (e.g., 29m 59s / 1,799s)")
    void testTimeoutSkippedIfUnder30Minutes() {
        String txnId = "TXN-0015";
        BigDecimal amount = new BigDecimal("12000.00");
        TransactionEntity txn = new TransactionEntity(
                txnId, "000000000015", "PAY", "rohit@okaxis", "aditya@oksbi",
                amount, "Loan", TransactionType.P2P,
                TransactionStatus.PENDING, null, BigDecimal.ZERO, initiatedAt, null, null
        );

        // Job runs at 10:29:59 (1,799s elapsed)
        LocalDateTime earlyTime = initiatedAt.plusSeconds(1799);

        when(transactionRepository.findByStatusAndInitiatedAtBefore(eq(TransactionStatus.PENDING), any()))
                .thenReturn(List.of(txn));
        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(txn));

        int reversed = timeoutJobService.processTimeouts(earlyTime);

        assertEquals(0, reversed, "Must not reverse transaction before 30-minute threshold");
        assertEquals(TransactionStatus.PENDING, txn.getStatus());
        verify(accountService, never()).creditAccount(anyLong(), any());
    }

    // =========================================================================
    // 12. SIMULTANEOUS TIMEOUT AND ACK CONCURRENCY
    // =========================================================================

    @Test
    @DisplayName("12. Simultaneous Timeout Job and ACK race condition: serialized safely via row lock")
    void testSimultaneousTimeoutAndAckRaceCondition() throws InterruptedException, ExecutionException {
        String txnId = "TXN-0099";
        BigDecimal amount = new BigDecimal("2500.00");
        TransactionEntity txn = new TransactionEntity(
                txnId, "000000000099", "PAY", "rohit@okaxis", "aditya@oksbi",
                amount, "Race test", TransactionType.P2P,
                TransactionStatus.PENDING, null, BigDecimal.ZERO, initiatedAt, null, null
        );

        LocalDateTime eventTime = initiatedAt.plusMinutes(30);

        // Real synchronized block simulating pessimistic DB row locking
        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenAnswer(inv -> {
            synchronized (txn) {
                return Optional.of(txn);
            }
        });
        when(transactionRepository.findByStatusAndInitiatedAtBefore(eq(TransactionStatus.PENDING), any()))
                .thenAnswer(inv -> {
                    synchronized (txn) {
                        return txn.getStatus() == TransactionStatus.PENDING ? List.of(txn) : Collections.emptyList();
                    }
                });

        AccountEntity payerAcc = new AccountEntity(1L, "100000000001", "UTIB001", "USR-001", null,
                new BigDecimal("50000.00"), "INR", 0L, eventTime, eventTime);
        when(accountService.creditAccount(eq(1L), any())).thenReturn(payerAcc);
        when(accountService.debitAccount(eq(1L), any())).thenReturn(payerAcc);
        when(accountService.getBalance(1L)).thenReturn(new BigDecimal("50000.00"));

        AccountEntity payeeAcc = new AccountEntity(2L, "100000000002", "SBIN001", "USR-002", null,
                new BigDecimal("60000.00"), "INR", 0L, eventTime, eventTime);
        when(accountService.creditAccount(eq(2L), any())).thenReturn(payeeAcc);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<Integer> timeoutFuture = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            return timeoutJobService.processTimeouts(eventTime);
        });

        Future<Boolean> ackFuture = executor.submit(() -> {
            readyLatch.countDown();
            startLatch.await();
            paymentService.handleBeneficiaryResponse(txnId, eventTime, "ACK");
            return true;
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Fire both simultaneously

        timeoutFuture.get();
        ackFuture.get();
        executor.shutdown();

        // Under serialized row locking, the final status MUST be either SUCCESS (ACK won) or DEEMED_SUCCESS (Timeout won)
        TransactionStatus finalStatus = txn.getStatus();
        assertTrue(finalStatus == TransactionStatus.SUCCESS || finalStatus == TransactionStatus.DEEMED_SUCCESS,
                "Final status must be either SUCCESS or DEEMED_SUCCESS, got: " + finalStatus);

        // Financial invariant: beneficiary was credited exactly once
        verify(accountService, times(1)).creditAccount(eq(2L), eq(amount));
    }

    // =========================================================================
    // 13. BALANCE BOUNDARY TESTS (EXACT, ONE RUPEE BELOW, ZERO)
    // =========================================================================

    @Test
    @DisplayName("13A. Late ACK when remitter balance equals exact original transaction amount -> DEEMED_SUCCESS")
    void testLateAckWhenBalanceEqualsExactAmount() {
        String txnId = "TXN-0050";
        BigDecimal amount = new BigDecimal("1400.00");
        LocalDateTime reversedAt = initiatedAt.plusMinutes(30);

        TransactionEntity reversedTxn = new TransactionEntity(
                txnId, "000000000050", "PAY", "rohit@okaxis", "aditya@oksbi",
                amount, "Exact balance test", TransactionType.P2P,
                TransactionStatus.REVERSED, null, BigDecimal.ZERO, initiatedAt, reversedAt, null
        );

        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(reversedTxn));
        when(accountService.getBalance(1L)).thenReturn(new BigDecimal("1400.00")); // Exactly equal!

        AccountEntity reDebitedAcc = new AccountEntity(1L, "100000000001", "UTIB001", "USR-001", null,
                BigDecimal.ZERO.setScale(2), "INR", 0L, reversedAt, reversedAt);
        when(accountService.debitAccount(1L, amount)).thenReturn(reDebitedAcc);

        AccountEntity payeeAcc = new AccountEntity(2L, "100000000002", "SBIN001", "USR-002", null,
                new BigDecimal("1400.00"), "INR", 0L, reversedAt, reversedAt);
        when(accountService.creditAccount(2L, amount)).thenReturn(payeeAcc);

        LocalDateTime lateAckTime = reversedAt.plusMinutes(5);
        paymentService.handleBeneficiaryResponse(txnId, lateAckTime, "ACK");

        assertEquals(TransactionStatus.DEEMED_SUCCESS, reversedTxn.getStatus());
        assertEquals(lateAckTime, reversedTxn.getDeemedSuccessAt());
        verify(accountService).debitAccount(1L, amount);
        verify(accountService).creditAccount(2L, amount);
    }

    @Test
    @DisplayName("13B. Late ACK when remitter balance is one rupee below original amount -> RECON_SHORTFALL")
    void testLateAckWhenBalanceIsOneRupeeBelowAmount() {
        String txnId = "TXN-0051";
        BigDecimal amount = new BigDecimal("1400.00");
        LocalDateTime reversedAt = initiatedAt.plusMinutes(30);

        TransactionEntity reversedTxn = new TransactionEntity(
                txnId, "000000000051", "PAY", "rohit@okaxis", "aditya@oksbi",
                amount, "Shortfall 1 rupee", TransactionType.P2P,
                TransactionStatus.REVERSED, null, BigDecimal.ZERO, initiatedAt, reversedAt, null
        );

        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(reversedTxn));
        when(accountService.getBalance(1L)).thenReturn(new BigDecimal("1399.00")); // Exactly 1 rupee below!

        LocalDateTime lateAckTime = reversedAt.plusMinutes(5);
        paymentService.handleBeneficiaryResponse(txnId, lateAckTime, "ACK");

        assertEquals(TransactionStatus.RECON_SHORTFALL, reversedTxn.getStatus());
        verify(accountService, never()).debitAccount(anyLong(), any());
        verify(reconciliationRecordRepository).save(argThat(r ->
                "RECON_SHORTFALL".equals(r.getIncidentType()) &&
                r.getDetails().contains("Exact shortfall amount: 1.00")
        ));
    }

    @Test
    @DisplayName("13C. Late ACK when remitter balance is zero -> RECON_SHORTFALL with full amount deficient")
    void testLateAckWhenBalanceIsZero() {
        String txnId = "TXN-0052";
        BigDecimal amount = new BigDecimal("1400.00");
        LocalDateTime reversedAt = initiatedAt.plusMinutes(30);

        TransactionEntity reversedTxn = new TransactionEntity(
                txnId, "000000000052", "PAY", "rohit@okaxis", "aditya@oksbi",
                amount, "Zero balance", TransactionType.P2P,
                TransactionStatus.REVERSED, null, BigDecimal.ZERO, initiatedAt, reversedAt, null
        );

        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(reversedTxn));
        when(accountService.getBalance(1L)).thenReturn(BigDecimal.ZERO.setScale(2));

        LocalDateTime lateAckTime = reversedAt.plusMinutes(10);
        paymentService.handleBeneficiaryResponse(txnId, lateAckTime, "ACK");

        assertEquals(TransactionStatus.RECON_SHORTFALL, reversedTxn.getStatus());
        verify(accountService, never()).debitAccount(anyLong(), any());
        verify(reconciliationRecordRepository).save(argThat(r ->
                "RECON_SHORTFALL".equals(r.getIncidentType()) &&
                r.getDetails().contains("Exact shortfall amount: 1400.00")
        ));
    }

    @Test
    @DisplayName("13D. Late ACK when remitter VPA is inactive -> RECON_SHORTFALL")
    void testLateAckWhenRemitterAccountInactive() {
        String txnId = "TXN-0053";
        BigDecimal amount = new BigDecimal("500.00");
        LocalDateTime reversedAt = initiatedAt.plusMinutes(30);

        VpaDetails inactivePayerVpa = new VpaDetails("rohit@okaxis", 1L, OwnerType.USER, "USR-001", initiatedAt.minusMonths(2), false);
        when(vpaService.getVpaOrThrow("rohit@okaxis")).thenReturn(inactivePayerVpa);

        TransactionEntity reversedTxn = new TransactionEntity(
                txnId, "000000000053", "PAY", "rohit@okaxis", "aditya@oksbi",
                amount, "Inactive VPA", TransactionType.P2P,
                TransactionStatus.REVERSED, null, BigDecimal.ZERO, initiatedAt, reversedAt, null
        );

        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(reversedTxn));
        when(accountService.getBalance(1L)).thenReturn(new BigDecimal("10000.00")); // Balance sufficient, but VPA inactive!

        LocalDateTime lateAckTime = reversedAt.plusMinutes(5);
        paymentService.handleBeneficiaryResponse(txnId, lateAckTime, "ACK");

        assertEquals(TransactionStatus.RECON_SHORTFALL, reversedTxn.getStatus());
        verify(accountService, never()).debitAccount(anyLong(), any());
    }

    // =========================================================================
    // 14. ILLEGAL STATE TRANSITIONS
    // =========================================================================

    @Test
    @DisplayName("14. Illegal state transitions are safely rejected by the state machine")
    void testIllegalStateTransitions() {
        // A. SUCCESS -> REVERSED is blocked (Timeout job skips SUCCESS)
        TransactionEntity successTxn = new TransactionEntity(
                "TXN-0060", "000000000060", "PAY", "rohit@okaxis", "aditya@oksbi",
                new BigDecimal("1000.00"), "Success", TransactionType.P2P,
                TransactionStatus.SUCCESS, null, BigDecimal.ZERO, initiatedAt, null, null
        );
        when(transactionRepository.findByTxnIdForUpdate("TXN-0060")).thenReturn(Optional.of(successTxn));
        when(transactionRepository.findByStatusAndInitiatedAtBefore(eq(TransactionStatus.PENDING), any()))
                .thenReturn(List.of(successTxn));

        int reversed = timeoutJobService.processTimeouts(initiatedAt.plusMinutes(35));
        assertEquals(0, reversed);
        assertEquals(TransactionStatus.SUCCESS, successTxn.getStatus());

        // B. FAILED -> SUCCESS is blocked (Late ACK after NACK records CONFLICTING_PACKET)
        TransactionEntity failedTxn = new TransactionEntity(
                "TXN-0061", "000000000061", "PAY", "rohit@okaxis", "aditya@oksbi",
                new BigDecimal("1000.00"), "Failed", TransactionType.P2P,
                TransactionStatus.FAILED, null, BigDecimal.ZERO, initiatedAt, null, null
        );
        when(transactionRepository.findByTxnIdForUpdate("TXN-0061")).thenReturn(Optional.of(failedTxn));
        when(accountService.getBalance(1L)).thenReturn(new BigDecimal("10000.00"));

        paymentService.handleBeneficiaryResponse("TXN-0061", initiatedAt.plusMinutes(5), "ACK");
        assertEquals(TransactionStatus.FAILED, failedTxn.getStatus());
        verify(reconciliationRecordRepository).save(argThat(r -> "CONFLICTING_PACKET".equals(r.getIncidentType())));

        // C. DEEMED_SUCCESS -> SUCCESS duplicate is blocked
        TransactionEntity deemedTxn = new TransactionEntity(
                "TXN-0062", "000000000062", "PAY", "rohit@okaxis", "aditya@oksbi",
                new BigDecimal("1000.00"), "Deemed", TransactionType.P2P,
                TransactionStatus.DEEMED_SUCCESS, null, BigDecimal.ZERO, initiatedAt, initiatedAt.plusMinutes(30), initiatedAt.plusMinutes(31)
        );
        when(transactionRepository.findByTxnIdForUpdate("TXN-0062")).thenReturn(Optional.of(deemedTxn));

        paymentService.handleBeneficiaryResponse("TXN-0062", initiatedAt.plusMinutes(35), "ACK");
        assertEquals(TransactionStatus.DEEMED_SUCCESS, deemedTxn.getStatus());

        // D. RECON_SHORTFALL -> SUCCESS duplicate is blocked
        TransactionEntity shortfallTxn = new TransactionEntity(
                "TXN-0063", "000000000063", "PAY", "rohit@okaxis", "aditya@oksbi",
                new BigDecimal("1000.00"), "Shortfall", TransactionType.P2P,
                TransactionStatus.RECON_SHORTFALL, null, BigDecimal.ZERO, initiatedAt, initiatedAt.plusMinutes(30), null
        );
        when(transactionRepository.findByTxnIdForUpdate("TXN-0063")).thenReturn(Optional.of(shortfallTxn));

        paymentService.handleBeneficiaryResponse("TXN-0063", initiatedAt.plusMinutes(40), "ACK");
        assertEquals(TransactionStatus.RECON_SHORTFALL, shortfallTxn.getStatus());
    }

    // =========================================================================
    // 15. BATCH AND MIDNIGHT BOUNDARY TESTS
    // =========================================================================

    @Test
    @DisplayName("15A. Multiple pending transactions: only those reaching 30 minutes are auto-reversed")
    void testMultiplePendingTransactionsProcessedByOneTimeoutJob() {
        LocalDateTime jobTime = initiatedAt.plusMinutes(30); // 10:30:00

        // Txn 1: initiated at 09:55:00 (35 min elapsed >= 30m -> eligible)
        TransactionEntity txn1 = new TransactionEntity("TXN-1", "000000000001", "PAY", "rohit@okaxis", "aditya@oksbi",
                new BigDecimal("100.00"), "T1", TransactionType.P2P, TransactionStatus.PENDING, null, BigDecimal.ZERO,
                initiatedAt.minusMinutes(5), null, null);

        // Txn 2: initiated at 10:00:00 (exact 30 min elapsed >= 30m -> eligible)
        TransactionEntity txn2 = new TransactionEntity("TXN-2", "000000000002", "PAY", "rohit@okaxis", "aditya@oksbi",
                new BigDecimal("200.00"), "T2", TransactionType.P2P, TransactionStatus.PENDING, null, BigDecimal.ZERO,
                initiatedAt, null, null);

        // Txn 3: initiated at 10:10:00 (20 min elapsed < 30m -> NOT eligible)
        TransactionEntity txn3 = new TransactionEntity("TXN-3", "000000000003", "PAY", "rohit@okaxis", "aditya@oksbi",
                new BigDecimal("300.00"), "T3", TransactionType.P2P, TransactionStatus.PENDING, null, BigDecimal.ZERO,
                initiatedAt.plusMinutes(10), null, null);

        when(transactionRepository.findByStatusAndInitiatedAtBefore(eq(TransactionStatus.PENDING), any()))
                .thenReturn(List.of(txn1, txn2, txn3));
        when(transactionRepository.findByTxnIdForUpdate("TXN-1")).thenReturn(Optional.of(txn1));
        when(transactionRepository.findByTxnIdForUpdate("TXN-2")).thenReturn(Optional.of(txn2));
        when(transactionRepository.findByTxnIdForUpdate("TXN-3")).thenReturn(Optional.of(txn3));

        AccountEntity payerAcc = new AccountEntity(1L, "100000000001", "UTIB001", "USR-001", null,
                new BigDecimal("50000.00"), "INR", 0L, jobTime, jobTime);
        when(accountService.creditAccount(eq(1L), any())).thenReturn(payerAcc);

        int reversed = timeoutJobService.processTimeouts(jobTime);

        assertEquals(2, reversed, "Exactly 2 of the 3 transactions reached 30m threshold");
        assertEquals(TransactionStatus.REVERSED, txn1.getStatus());
        assertEquals(TransactionStatus.REVERSED, txn2.getStatus());
        assertEquals(TransactionStatus.PENDING, txn3.getStatus());
    }

    @Test
    @DisplayName("15B. Timeout job with empty eligible list returns 0 with no updates")
    void testTimeoutJobWithNoEligibleTransactions() {
        when(transactionRepository.findByStatusAndInitiatedAtBefore(eq(TransactionStatus.PENDING), any()))
                .thenReturn(Collections.emptyList());

        int reversed = timeoutJobService.processTimeouts(initiatedAt.plusHours(1));
        assertEquals(0, reversed);
        verify(accountService, never()).creditAccount(anyLong(), any());
        verify(ledgerService, never()).recordEntry(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("15C. Midnight boundary: auto-reversal after midnight decrements counter for initiation date")
    void testMidnightBoundaryDuringPendingAndReversal() {
        LocalDateTime day1Init = LocalDateTime.of(2026, 10, 1, 23, 45, 0); // 15 mins before midnight
        LocalDate day1Date = LocalDate.of(2026, 10, 1);
        LocalDateTime day2Timeout = LocalDateTime.of(2026, 10, 2, 0, 15, 0); // 15 mins after midnight

        TransactionEntity txn = new TransactionEntity("TXN-MID", "000000000MID", "PAY", "rohit@okaxis", "aditya@oksbi",
                new BigDecimal("5000.00"), "Midnight test", TransactionType.P2P, TransactionStatus.PENDING, null,
                BigDecimal.ZERO, day1Init, null, null);

        when(transactionRepository.findByStatusAndInitiatedAtBefore(eq(TransactionStatus.PENDING), any()))
                .thenReturn(List.of(txn));
        when(transactionRepository.findByTxnIdForUpdate("TXN-MID")).thenReturn(Optional.of(txn));

        AccountEntity payerAcc = new AccountEntity(1L, "100000000001", "UTIB001", "USR-001", null,
                new BigDecimal("50000.00"), "INR", 0L, day2Timeout, day2Timeout);
        when(accountService.creditAccount(eq(1L), any())).thenReturn(payerAcc);

        int reversed = timeoutJobService.processTimeouts(day2Timeout);

        assertEquals(1, reversed);
        assertEquals(TransactionStatus.REVERSED, txn.getStatus());

        // Crucial invariant: Decrement counter for Day 1 (initiation date), NOT Day 2 (timeout date)!
        verify(counterService).decrementDaily("USR-001", day1Date, new BigDecimal("5000.00"));
        verify(counterService, never()).decrementDaily("USR-001", LocalDate.of(2026, 10, 2), new BigDecimal("5000.00"));
    }
}

