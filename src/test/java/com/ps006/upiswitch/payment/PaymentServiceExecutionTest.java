package com.ps006.upiswitch.payment;

import com.ps006.upiswitch.account.entity.AccountEntity;
import com.ps006.upiswitch.account.model.AccountDetails;
import com.ps006.upiswitch.account.service.AccountService;
import com.ps006.upiswitch.common.repository.ReconciliationRecordRepository;
import com.ps006.upiswitch.common.util.RrnGenerator;
import com.ps006.upiswitch.counter.entity.DailyCounterEntity;
import com.ps006.upiswitch.counter.entity.MonthlyCounterEntity;
import com.ps006.upiswitch.counter.model.DailyCounterModel;
import com.ps006.upiswitch.counter.service.CounterService;
import com.ps006.upiswitch.ledger.model.LegType;
import com.ps006.upiswitch.ledger.service.LedgerService;
import com.ps006.upiswitch.merchant.service.MerchantService;
import com.ps006.upiswitch.payment.entity.BeneficiaryPaymentHistoryEntity;
import com.ps006.upiswitch.payment.entity.TransactionEntity;
import com.ps006.upiswitch.payment.model.PaymentResult;
import com.ps006.upiswitch.payment.model.TransactionStatus;
import com.ps006.upiswitch.payment.model.TransactionType;
import com.ps006.upiswitch.payment.repository.BeneficiaryPaymentHistoryRepository;
import com.ps006.upiswitch.payment.repository.TransactionEventRepository;
import com.ps006.upiswitch.payment.repository.TransactionRepository;
import com.ps006.upiswitch.payment.service.PaymentService;
import com.ps006.upiswitch.reporting.model.TxnLogRow;
import com.ps006.upiswitch.user.model.UserProfile;
import com.ps006.upiswitch.user.service.UserService;
import com.ps006.upiswitch.validation.model.ValidationResult;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentServiceExecutionTest {

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

    private PaymentService paymentService;

    private final LocalDateTime now = LocalDateTime.of(2026, 10, 1, 9, 10, 0);
    private final LocalDate today = LocalDate.of(2026, 10, 1);

    private VpaDetails payerVpa;
    private VpaDetails payeeVpa;
    private AccountEntity payerAccountEntity;
    private AccountEntity payeeAccountEntity;

    @BeforeEach
    void setUp() {
        RrnGenerator.resetSequence();

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

        payerVpa = new VpaDetails("rohit@okaxis", 1L, OwnerType.USER, "USR-001", now.minusMonths(2), true);
        payeeVpa = new VpaDetails("aditya@oksbi", 2L, OwnerType.USER, "USR-002", now.minusMonths(2), true);

        payerAccountEntity = new AccountEntity(
                1L, "100000000001", "UTIB001", "USR-001", null,
                new BigDecimal("50000.00"), "INR", 0L, now.minusMonths(2), now.minusMonths(2)
        );
        payeeAccountEntity = new AccountEntity(
                2L, "100000000002", "SBIN001", "USR-002", null,
                new BigDecimal("120000.00"), "INR", 0L, now.minusMonths(2), now.minusMonths(2)
        );

        when(vpaService.findByVpa("rohit@okaxis")).thenReturn(Optional.of(payerVpa));
        when(vpaService.findByVpa("aditya@oksbi")).thenReturn(Optional.of(payeeVpa));
        when(vpaService.getVpaOrThrow("rohit@okaxis")).thenReturn(payerVpa);
        when(vpaService.getVpaOrThrow("aditya@oksbi")).thenReturn(payeeVpa);

        when(userService.findById("USR-001")).thenReturn(Optional.of(new UserProfile("USR-001", "Rohit", "9999999991", null, now.minusMonths(2))));
        when(userService.findById("USR-002")).thenReturn(Optional.of(new UserProfile("USR-002", "Aditya", "9999999992", null, now.minusMonths(2))));

        when(accountService.findById(1L)).thenReturn(Optional.of(new AccountDetails(1L, "100000000001", "UTIB001", "USR-001", null, new BigDecimal("50000.00"), "INR", 0L, now, now)));
        when(accountService.findById(2L)).thenReturn(Optional.of(new AccountDetails(2L, "100000000002", "SBIN001", "USR-002", null, new BigDecimal("120000.00"), "INR", 0L, now, now)));

        when(accountService.getEntityForUpdate(1L)).thenReturn(payerAccountEntity);
        when(accountService.getEntityForUpdate(2L)).thenReturn(payeeAccountEntity);

        when(counterService.getDailyCounter("USR-001", today))
                .thenReturn(new DailyCounterModel(1L, "USR-001", today, 0, BigDecimal.ZERO));

        when(validationPipelineService.validateIdentityAndStructure(any())).thenReturn(ValidationResult.success());
        when(validationPipelineService.validate(any())).thenReturn(ValidationResult.success());
    }

    // =========================================================================
    // 1. PAYMENT INITIATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Verify successful payment execution: remitter debited, ledger posted, counters incremented, returns PENDING")
    void testSuccessfulPaymentInitiation() {
        BigDecimal amount = new BigDecimal("8000.00");
        AccountEntity debitedEntity = new AccountEntity(
                1L, "100000000001", "UTIB001", "USR-001", null,
                new BigDecimal("42000.00"), "INR", 0L, now, now
        );
        when(accountService.debitAccount(1L, amount)).thenReturn(debitedEntity);

        PaymentResult result = paymentService.processPaymentResult(
                "TXN-0001", now, "rohit@okaxis", "aditya@oksbi", amount, "bill"
        );

        assertNotNull(result);
        assertEquals("TXN-0001", result.txnId());
        assertEquals("000000000001", result.rrn(), "Deterministic 12-digit RRN expected");
        assertEquals(TransactionStatus.PENDING, result.status(), "Initial payment state must be PENDING");
        assertTrue(result.ruleIds().isEmpty(), "No rule violations on success");
        assertEquals(now, result.timestamp());

        // Verify account lock and debit
        verify(accountService).getEntityForUpdate(1L);
        verify(accountService).debitAccount(1L, amount);

        // Verify append-only debit ledger entry
        verify(ledgerService).recordDebit(eq("TXN-0001"), eq(1L), eq("rohit@okaxis"), eq(amount),
                eq(new BigDecimal("42000.00")), eq(now), eq("bill"));

        // Verify counter increment
        verify(counterService).incrementDaily("USR-001", today, amount);
        verify(counterService).incrementMonthly("USR-001", today, amount);

        // Verify transaction saved in PENDING state
        ArgumentCaptor<TransactionEntity> txnCaptor = ArgumentCaptor.forClass(TransactionEntity.class);
        verify(transactionRepository).save(txnCaptor.capture());
        TransactionEntity savedTxn = txnCaptor.getValue();
        assertEquals(TransactionStatus.PENDING, savedTxn.getStatus());
        assertEquals("TXN-0001", savedTxn.getTxnId());
        assertEquals("000000000001", savedTxn.getRrn());
    }

    @Test
    @DisplayName("Verify TxnLogRow adapter preserves physical line number and empty rule_ids on success")
    void testTxnLogRowEmission() {
        BigDecimal amount = new BigDecimal("8000.00");
        when(accountService.debitAccount(1L, amount)).thenReturn(payerAccountEntity);

        TxnLogRow logRow = paymentService.processPayment(
                3, "TXN-0001", now, "rohit@okaxis", "aditya@oksbi", amount, "bill"
        );

        assertEquals(3, logRow.lineNo());
        assertEquals("TXN-0001", logRow.txnId());
        assertEquals("PAY", logRow.command());
        assertEquals("PENDING", logRow.status());
        assertEquals("000000000001", logRow.rrn());
        assertEquals("", logRow.ruleIds());
    }

    // =========================================================================
    // 2. BENEFICIARY RESPONSE HANDLING (ACK, NACK, NO_RESPONSE)
    // =========================================================================

    @Test
    @DisplayName("Verify BENE_RESPONSE ACK: credits beneficiary, writes CREDIT ledger, transitions to SUCCESS")
    void testBeneficiaryResponseAck() {
        TransactionEntity pendingTxn = new TransactionEntity(
                "TXN-0001", "000000000001", "PAY", "rohit@okaxis", "aditya@oksbi",
                new BigDecimal("8000.00"), "bill", TransactionType.P2P,
                TransactionStatus.PENDING, null, BigDecimal.ZERO, now, null, null
        );
        when(transactionRepository.findByTxnIdForUpdate("TXN-0001")).thenReturn(Optional.of(pendingTxn));

        AccountEntity creditedEntity = new AccountEntity(
                2L, "100000000002", "SBIN001", "USR-002", null,
                new BigDecimal("128000.00"), "INR", 0L, now, now
        );
        when(accountService.creditAccount(2L, new BigDecimal("8000.00"))).thenReturn(creditedEntity);

        LocalDateTime ackTime = now.plusSeconds(3);
        paymentService.handleBeneficiaryResponse("TXN-0001", ackTime, "ACK");

        // Verify beneficiary credit
        verify(accountService).creditAccount(2L, new BigDecimal("8000.00"));
        verify(ledgerService).recordCredit(eq("TXN-0001"), eq(2L), eq("aditya@oksbi"),
                eq(new BigDecimal("8000.00")), eq(new BigDecimal("128000.00")), eq(ackTime), anyString());

        // Verify transaction updated to SUCCESS
        assertEquals(TransactionStatus.SUCCESS, pendingTxn.getStatus());
        verify(transactionRepository).save(pendingTxn);
    }

    @Test
    @DisplayName("Verify BENE_RESPONSE NACK: refunds remitter, writes REVERSAL ledger, restores counters, transitions to FAILED")
    void testBeneficiaryResponseNack() {
        TransactionEntity pendingTxn = new TransactionEntity(
                "TXN-0033", "000000000033", "PAY", "rohit@okaxis", "aditya@oksbi",
                new BigDecimal("500.00"), "test", TransactionType.P2P,
                TransactionStatus.PENDING, null, BigDecimal.ZERO, now, null, null
        );
        when(transactionRepository.findByTxnIdForUpdate("TXN-0033")).thenReturn(Optional.of(pendingTxn));

        AccountEntity refundedEntity = new AccountEntity(
                1L, "100000000001", "UTIB001", "USR-001", null,
                new BigDecimal("50000.00"), "INR", 0L, now, now
        );
        when(accountService.creditAccount(1L, new BigDecimal("500.00"))).thenReturn(refundedEntity);

        LocalDateTime nackTime = now.plusSeconds(4);
        paymentService.handleBeneficiaryResponse("TXN-0033", nackTime, "NACK");

        // Verify remitter refund
        verify(accountService).creditAccount(1L, new BigDecimal("500.00"));
        verify(ledgerService).recordEntry(eq("TXN-0033"), eq(1L), eq("rohit@okaxis"),
                eq(LegType.REVERSAL), isNull(), eq(new BigDecimal("500.00")),
                eq(new BigDecimal("50000.00")), eq(nackTime), isNull(), anyString());

        // Verify counters restored
        verify(counterService).decrementDaily("USR-001", today, new BigDecimal("500.00"));
        verify(counterService).decrementMonthly("USR-001", today, new BigDecimal("500.00"));

        // Verify transaction marked FAILED
        assertEquals(TransactionStatus.FAILED, pendingTxn.getStatus());
        verify(transactionRepository).save(pendingTxn);
    }

    @Test
    @DisplayName("Verify BENE_RESPONSE NO_RESPONSE: retains PENDING state")
    void testBeneficiaryResponseNoResponse() {
        TransactionEntity pendingTxn = new TransactionEntity(
                "TXN-0015", "000000000015", "PAY", "rohit@okaxis", "aditya@oksbi",
                new BigDecimal("12000.00"), "loan", TransactionType.P2P,
                TransactionStatus.PENDING, null, BigDecimal.ZERO, now, null, null
        );
        when(transactionRepository.findByTxnIdForUpdate("TXN-0015")).thenReturn(Optional.of(pendingTxn));

        LocalDateTime noRespTime = now.plusSeconds(2);
        paymentService.handleBeneficiaryResponse("TXN-0015", noRespTime, "NO_RESPONSE");

        // Verify no money was moved
        verify(accountService, never()).creditAccount(anyLong(), any());
        verify(accountService, never()).debitAccount(anyLong(), any());

        // Status remains PENDING
        assertEquals(TransactionStatus.PENDING, pendingTxn.getStatus());
    }

    // =========================================================================
    // 3. IDEMPOTENCY AND OUT-OF-ORDER PACKETS
    // =========================================================================

    @Test
    @DisplayName("Verify re-used txn_id is rejected as duplicate without moving funds")
    void testDuplicateTxnIdRejection() {
        when(transactionRepository.findByTxnId("TXN-0001")).thenReturn(Optional.of(new TransactionEntity()));

        PaymentResult result = paymentService.processPaymentResult(
                "TXN-0001", now, "rohit@okaxis", "aditya@oksbi", new BigDecimal("500.00"), "replay"
        );

        assertEquals(TransactionStatus.FAILED, result.status());
        assertEquals("DUPLICATE_TXN_ID", result.getPrimaryRuleId());
        verify(accountService, never()).debitAccount(anyLong(), any());
        verify(reconciliationRecordRepository).save(any());
    }

    @Test
    @DisplayName("Verify out-of-order packet (BENE_RESPONSE before PAY) recorded in reconciliation_records")
    void testOutOfOrderPacketHandling() {
        when(transactionRepository.findByTxnIdForUpdate("TXN-0038")).thenReturn(Optional.empty());

        paymentService.handleBeneficiaryResponse("TXN-0038", now, "ACK");

        verify(reconciliationRecordRepository).save(argThat(r ->
                "OUT_OF_ORDER_PACKET".equals(r.getRecordType()) && "TXN-0038".equals(r.getTxnId())
        ));
    }
}

