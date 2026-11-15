package com.ps006.upiswitch.collect;

import com.ps006.upiswitch.account.entity.AccountEntity;
import com.ps006.upiswitch.account.model.AccountDetails;
import com.ps006.upiswitch.account.service.AccountService;
import com.ps006.upiswitch.collect.entity.CollectRequestEntity;
import com.ps006.upiswitch.collect.model.CollectStatus;
import com.ps006.upiswitch.collect.repository.CollectRepository;
import com.ps006.upiswitch.collect.service.CollectApprovalService;
import com.ps006.upiswitch.collect.service.CollectDeclineService;
import com.ps006.upiswitch.collect.service.CollectExpiryService;
import com.ps006.upiswitch.collect.service.CollectService;
import com.ps006.upiswitch.common.util.RrnGenerator;
import com.ps006.upiswitch.counter.model.DailyCounterModel;
import com.ps006.upiswitch.counter.service.CounterService;
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
import com.ps006.upiswitch.reporting.model.TxnLogRow;
import com.ps006.upiswitch.user.model.UserProfile;
import com.ps006.upiswitch.user.service.UserService;
import com.ps006.upiswitch.validation.model.PaymentValidationContext;
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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive test suite for Phase 7: UPI Collect Request Engine.
 * Verifies all rules from MASTER PROMPT 07:
 * - Default 30-min and max 72-hour expiry.
 * - PIN threshold boundary (₹2,000.00 vs ₹2,000.01).
 * - Approval before, at, and after expiry.
 * - Decline and velocity isolation.
 * - Limit enforcement via existing ValidationPipelineService during approval.
 * - Duplicate approval, approval after decline, and approval after expiry prevention.
 */
class CollectEngineComprehensiveTest {

    private CollectRepository collectRepository;
    private TransactionRepository transactionRepository;
    private TransactionEventRepository transactionEventRepository;
    private BeneficiaryPaymentHistoryRepository beneficiaryPaymentHistoryRepository;
    private VpaService vpaService;
    private AccountService accountService;
    private UserService userService;
    private MerchantService merchantService;
    private CounterService counterService;
    private LedgerService ledgerService;
    private ValidationPipelineService validationPipelineService;

    private CollectExpiryService collectExpiryService;
    private CollectApprovalService collectApprovalService;
    private CollectDeclineService collectDeclineService;
    private CollectService collectService;

    private final LocalDateTime now = LocalDateTime.of(2026, 10, 11, 12, 0, 0);
    private final LocalDate today = now.toLocalDate();

    private final VpaDetails payerVpa = new VpaDetails("geetha308@ybl", 10L, OwnerType.USER, "USR-004", now.minusMonths(3), true);
    private final VpaDetails requesterVpa = new VpaDetails("udupi@icici", 20L, OwnerType.MERCHANT, "MER-001", now.minusMonths(6), true);

    @BeforeEach
    void setUp() {
        RrnGenerator.resetSequence();

        collectRepository = mock(CollectRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        transactionEventRepository = mock(TransactionEventRepository.class);
        beneficiaryPaymentHistoryRepository = mock(BeneficiaryPaymentHistoryRepository.class);
        vpaService = mock(VpaService.class);
        accountService = mock(AccountService.class);
        userService = mock(UserService.class);
        merchantService = mock(MerchantService.class);
        counterService = mock(CounterService.class);
        ledgerService = mock(LedgerService.class);
        validationPipelineService = mock(ValidationPipelineService.class);

        collectExpiryService = new CollectExpiryService(collectRepository, transactionRepository, transactionEventRepository);

        collectApprovalService = new CollectApprovalService(
                collectRepository,
                transactionRepository,
                transactionEventRepository,
                beneficiaryPaymentHistoryRepository,
                collectExpiryService,
                vpaService,
                accountService,
                userService,
                merchantService,
                counterService,
                ledgerService,
                validationPipelineService
        );

        collectDeclineService = new CollectDeclineService(
                collectRepository,
                transactionRepository,
                transactionEventRepository
        );

        collectService = new CollectService(
                collectRepository,
                transactionRepository,
                transactionEventRepository,
                collectExpiryService,
                collectApprovalService,
                collectDeclineService,
                vpaService
        );

        when(vpaService.getVpaOrThrow("geetha308@ybl")).thenReturn(payerVpa);
        when(vpaService.getVpaOrThrow("udupi@icici")).thenReturn(requesterVpa);
        when(vpaService.findByVpa("geetha308@ybl")).thenReturn(Optional.of(payerVpa));
        when(vpaService.findByVpa("udupi@icici")).thenReturn(Optional.of(requesterVpa));
    }

    // =========================================================================
    // 1. PIN THRESHOLD BOUNDARY TESTS (₹2,000.00 vs ₹2,000.01)
    // =========================================================================

    @Test
    @DisplayName("1. Rule 4: Exactly ₹2,000.00 does not require PIN flag (pin_flag=false allowed)")
    void testCollectExactTwoThousandWithoutPin() {
        String txnId = "TXN-0019";
        BigDecimal amount = new BigDecimal("2000.00");

        when(transactionRepository.findByTxnId(txnId)).thenReturn(Optional.empty());

        TxnLogRow row = collectService.initiateCollect(
                49, txnId, now, "udupi@icici", "geetha308@ybl", amount, 30, false
        );

        assertEquals("PENDING", row.status());
        assertEquals(txnId, row.txnId());
        assertEquals(new BigDecimal("2000.00"), row.amount());
        assertTrue(row.ruleIds().isEmpty(), "No rule breach for exact 2,000 without PIN");

        verify(collectRepository).save(argThat(c ->
                txnId.equals(c.getTxnId()) &&
                CollectStatus.PENDING == c.getStatus() &&
                !c.isPinFlag()
        ));
        verify(transactionRepository).save(argThat(t ->
                TransactionStatus.PENDING == t.getStatus() && "COLLECT".equals(t.getCommand())
        ));
    }

    @Test
    @DisplayName("2. Rule 3: Amount strictly greater than ₹2,000.00 requires PIN flag; ₹2,000.01 without PIN fails BR-07")
    void testCollectOverTwoThousandWithoutPin() {
        String txnId = "TXN-0020";
        BigDecimal amount = new BigDecimal("2000.01");

        TxnLogRow row = collectService.initiateCollect(
                51, txnId, now, "udupi@icici", "geetha308@ybl", amount, 30, false
        );

        assertEquals("FAILED", row.status());
        assertEquals("BR-07", row.ruleIds());

        verify(transactionRepository).save(argThat(t ->
                TransactionStatus.FAILED == t.getStatus() && "BR-07".equals(t.getRuleIds())
        ));
        verify(collectRepository).save(argThat(c ->
                CollectStatus.FAILED == c.getStatus()
        ));
    }

    @Test
    @DisplayName("3. Amount strictly greater than ₹2,000.00 with pin_flag=true is valid")
    void testCollectOverTwoThousandWithPin() {
        String txnId = "TXN-0020-PIN";
        BigDecimal amount = new BigDecimal("2000.01");

        when(transactionRepository.findByTxnId(txnId)).thenReturn(Optional.empty());

        TxnLogRow row = collectService.initiateCollect(
                51, txnId, now, "udupi@icici", "geetha308@ybl", amount, 30, true
        );

        assertEquals("PENDING", row.status());
        assertTrue(row.ruleIds().isEmpty());
    }

    // =========================================================================
    // 2. EXPIRY BOUNDARY TESTS (DEFAULT 30m, MAX 72h / 4320m)
    // =========================================================================

    @Test
    @DisplayName("4. Rule 1: Default expiry is 30 minutes when expiry <= 0")
    void testCollectDefaultExpiryThirtyMinutes() {
        String txnId = "TXN-DEF-EXP";
        BigDecimal amount = new BigDecimal("1000.00");

        when(transactionRepository.findByTxnId(txnId)).thenReturn(Optional.empty());

        TxnLogRow row = collectService.initiateCollect(
                1, txnId, now, "udupi@icici", "geetha308@ybl", amount, 0, false
        );

        assertEquals("PENDING", row.status());
        verify(collectRepository).save(argThat(c ->
                c.getExpiryMinutes() == 30 &&
                now.plusMinutes(30).equals(c.getExpiresAt())
        ));
    }

    @Test
    @DisplayName("5. Rule 2: Maximum expiry of 72 hours (4,320 minutes) is valid")
    void testCollectMaxExpirySeventyTwoHours() {
        String txnId = "TXN-MAX-EXP";
        BigDecimal amount = new BigDecimal("3500.00");

        when(transactionRepository.findByTxnId(txnId)).thenReturn(Optional.empty());

        TxnLogRow row = collectService.initiateCollect(
                1, txnId, now, "udupi@icici", "geetha308@ybl", amount, 4320, true
        );

        assertEquals("PENDING", row.status());
        verify(collectRepository).save(argThat(c ->
                c.getExpiryMinutes() == 4320 &&
                now.plusMinutes(4320).equals(c.getExpiresAt())
        ));
    }

    @Test
    @DisplayName("6. Rule 2: Expiry exceeding 72 hours (4,321 minutes) fails BR-07")
    void testCollectExceedingMaxExpiry() {
        String txnId = "TXN-0021";
        BigDecimal amount = new BigDecimal("3500.00");

        TxnLogRow row = collectService.initiateCollect(
                52, txnId, now, "udupi@icici", "geetha308@ybl", amount, 4321, true
        );

        assertEquals("FAILED", row.status());
        assertEquals("BR-07", row.ruleIds());
        verify(transactionRepository).save(argThat(t -> TransactionStatus.FAILED == t.getStatus()));
    }

    // =========================================================================
    // 3. APPROVAL TIMELINESS (BEFORE, EXACTLY AT, AND AFTER EXPIRY)
    // =========================================================================

    @Test
    @DisplayName("7. Rule 7: Approval before expiry debits payer, credits payee, increments counters, sets SUCCESS")
    void testCollectApprovalBeforeExpiry() {
        String txnId = "TXN-0019";
        BigDecimal amount = new BigDecimal("2000.00");
        LocalDateTime expiresAt = now.plusMinutes(30);

        CollectRequestEntity collect = new CollectRequestEntity(
                txnId, "udupi@icici", "geetha308@ybl", amount, 30, false, expiresAt, CollectStatus.PENDING
        );
        TransactionEntity txn = new TransactionEntity(
                txnId, "000000000019", "COLLECT", "geetha308@ybl", "udupi@icici",
                amount, "Collect", TransactionType.P2M, TransactionStatus.PENDING, null,
                BigDecimal.ZERO, now, null, null
        );

        when(collectRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(collect));
        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(txn));

        // Validation passed
        when(validationPipelineService.validateIdentityAndStructure(any())).thenReturn(ValidationResult.success());
        when(validationPipelineService.validate(any())).thenReturn(ValidationResult.success());

        // Account balances
        AccountEntity payerAcc = new AccountEntity(10L, "100000000010", "YBL001", "USR-004", null,
                new BigDecimal("50000.00"), "INR", 0L, now, now);
        when(accountService.getEntityForUpdate(10L)).thenReturn(payerAcc);
        when(accountService.getEntityForUpdate(20L)).thenReturn(new AccountEntity(20L, "200000000020", "ICIC001", null, "MER-001",
                new BigDecimal("100000.00"), "INR", 0L, now, now));

        AccountEntity debitedPayer = new AccountEntity(10L, "100000000010", "YBL001", "USR-004", null,
                new BigDecimal("48000.00"), "INR", 0L, now, now);
        when(accountService.debitAccount(10L, amount)).thenReturn(debitedPayer);

        AccountEntity creditedPayee = new AccountEntity(20L, "200000000020", "ICIC001", null, "MER-001",
                new BigDecimal("102000.00"), "INR", 0L, now, now);
        when(accountService.creditAccount(20L, amount)).thenReturn(creditedPayee);

        LocalDateTime approveTime = now.plusMinutes(5); // 12:05:00
        TxnLogRow row = collectService.approveCollect(50, txnId, approveTime);

        assertEquals("SUCCESS", row.status());
        assertEquals(CollectStatus.SUCCESS, collect.getStatus());
        assertEquals(TransactionStatus.SUCCESS, txn.getStatus());
        assertEquals(approveTime, collect.getApprovedAt());

        // Verify ledger entries
        verify(ledgerService).recordDebit(eq(txnId), eq(10L), eq("geetha308@ybl"), eq(amount),
                eq(new BigDecimal("48000.00")), eq(approveTime), anyString());
        verify(ledgerService).recordCredit(eq(txnId), eq(20L), eq("udupi@icici"), eq(amount),
                eq(new BigDecimal("102000.00")), eq(approveTime), anyString());

        // Verify Rule 7: Approved collects count as successful debits
        verify(counterService).incrementDaily("USR-004", today, amount);
        verify(counterService).incrementMonthly("USR-004", today, amount);
    }

    @Test
    @DisplayName("8. Approval exactly at expiry timestamp (t == expiresAt) is allowed (not expired)")
    void testCollectApprovalExactlyAtExpiry() {
        String txnId = "TXN-EXACT-EXP";
        BigDecimal amount = new BigDecimal("1000.00");
        LocalDateTime expiresAt = now.plusMinutes(30);

        CollectRequestEntity collect = new CollectRequestEntity(
                txnId, "udupi@icici", "geetha308@ybl", amount, 30, false, expiresAt, CollectStatus.PENDING
        );
        TransactionEntity txn = new TransactionEntity(
                txnId, "000000000099", "COLLECT", "geetha308@ybl", "udupi@icici",
                amount, "Collect", TransactionType.P2M, TransactionStatus.PENDING, null,
                BigDecimal.ZERO, now, null, null
        );

        when(collectRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(collect));
        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(txn));
        when(validationPipelineService.validateIdentityAndStructure(any())).thenReturn(ValidationResult.success());
        when(validationPipelineService.validate(any())).thenReturn(ValidationResult.success());

        AccountEntity payerAcc = new AccountEntity(10L, "100000000010", "YBL001", "USR-004", null,
                new BigDecimal("50000.00"), "INR", 0L, now, now);
        when(accountService.getEntityForUpdate(anyLong())).thenReturn(payerAcc);
        when(accountService.debitAccount(eq(10L), any())).thenReturn(payerAcc);
        when(accountService.creditAccount(eq(20L), any())).thenReturn(payerAcc);

        // Approval at exactly 12:30:00 (exact expiresAt)
        TxnLogRow row = collectService.approveCollect(1, txnId, expiresAt);

        assertEquals("SUCCESS", row.status());
        assertEquals(CollectStatus.SUCCESS, collect.getStatus());
    }

    @Test
    @DisplayName("9. Rule 5 & 11: Approval after expiry timestamp (t > expiresAt) sets EXPIRED with zero funds moved")
    void testCollectApprovalAfterExpiry() {
        String txnId = "TXN-0022";
        BigDecimal amount = new BigDecimal("500.00");
        LocalDateTime expiresAt = now.plusMinutes(30); // 12:30:00

        CollectRequestEntity collect = new CollectRequestEntity(
                txnId, "udupi@icici", "geetha308@ybl", amount, 30, false, expiresAt, CollectStatus.PENDING
        );
        TransactionEntity txn = new TransactionEntity(
                txnId, "000000000022", "COLLECT", "geetha308@ybl", "udupi@icici",
                amount, "Collect", TransactionType.P2M, TransactionStatus.PENDING, null,
                BigDecimal.ZERO, now, null, null
        );

        when(collectRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(collect));
        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(txn));

        // Approval arrives at 12:31:00 (31 min after initiation -> EXPIRED)
        LocalDateTime lateApproveTime = now.plusMinutes(31);
        TxnLogRow row = collectService.approveCollect(54, txnId, lateApproveTime);

        assertEquals("EXPIRED", row.status());
        assertEquals(CollectStatus.EXPIRED, collect.getStatus());
        assertEquals(TransactionStatus.EXPIRED, txn.getStatus());

        // Zero funds moved
        verify(accountService, never()).debitAccount(anyLong(), any());
        verify(accountService, never()).creditAccount(anyLong(), any());
        verify(ledgerService, never()).recordDebit(any(), any(), any(), any(), any(), any(), any());
        verify(counterService, never()).incrementDaily(any(), any(), any());
    }

    // =========================================================================
    // 4. DECLINE & VELOCITY ISOLATION
    // =========================================================================

    @Test
    @DisplayName("10. Rule 6: Declined collect sets DECLINED; does NOT count towards velocity or daily amount")
    void testCollectDecline() {
        String txnId = "TXN-0023";
        BigDecimal amount = new BigDecimal("700.00");

        CollectRequestEntity collect = new CollectRequestEntity(
                txnId, "keerthi335@okaxis", "anjali374@paytm", amount, 30, false, now.plusMinutes(30), CollectStatus.PENDING
        );
        TransactionEntity txn = new TransactionEntity(
                txnId, "000000000023", "COLLECT", "anjali374@paytm", "keerthi335@okaxis",
                amount, "Collect", TransactionType.P2P, TransactionStatus.PENDING, null,
                BigDecimal.ZERO, now, null, null
        );

        when(collectRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(collect));
        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(txn));

        LocalDateTime declineTime = now.plusMinutes(10);
        TxnLogRow row = collectService.declineCollect(56, txnId, declineTime);

        assertEquals("DECLINED", row.status());
        assertEquals(CollectStatus.DECLINED, collect.getStatus());
        assertEquals(TransactionStatus.DECLINED, txn.getStatus());
        assertEquals(declineTime, collect.getDeclinedAt());

        // Zero financial or counter impact
        verify(accountService, never()).debitAccount(anyLong(), any());
        verify(accountService, never()).creditAccount(anyLong(), any());
        verify(counterService, never()).incrementDaily(any(), any(), any());
        verify(counterService, never()).incrementMonthly(any(), any(), any());
    }

    // =========================================================================
    // 5. STATE MACHINE PRESERVATION & IDEMPOTENCY
    // =========================================================================

    @Test
    @DisplayName("11. Rule 9: Prevent duplicate approvals idempotently without second debit")
    void testDuplicateApproval() {
        String txnId = "TXN-0019";
        BigDecimal amount = new BigDecimal("2000.00");

        CollectRequestEntity collect = new CollectRequestEntity(
                txnId, "udupi@icici", "geetha308@ybl", amount, 30, false, now.plusMinutes(30), CollectStatus.SUCCESS
        );
        TransactionEntity txn = new TransactionEntity(
                txnId, "000000000019", "COLLECT", "geetha308@ybl", "udupi@icici",
                amount, "Collect", TransactionType.P2M, TransactionStatus.SUCCESS, null,
                BigDecimal.ZERO, now, null, null
        );

        when(collectRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(collect));
        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(txn));

        // Duplicate approval attempt
        TxnLogRow row = collectService.approveCollect(50, txnId, now.plusMinutes(6));

        assertEquals("SUCCESS", row.status());
        verify(accountService, never()).debitAccount(anyLong(), any());
        verify(ledgerService, never()).recordDebit(any(), any(), any(), any(), any(), any(), any());
        verify(counterService, never()).incrementDaily(any(), any(), any());
    }

    @Test
    @DisplayName("12. Rule 10: Prevent approval after decline")
    void testDeclineFollowedByApproval() {
        String txnId = "TXN-0023";
        BigDecimal amount = new BigDecimal("700.00");

        CollectRequestEntity collect = new CollectRequestEntity(
                txnId, "keerthi335@okaxis", "anjali374@paytm", amount, 30, false, now.plusMinutes(30), CollectStatus.DECLINED
        );
        TransactionEntity txn = new TransactionEntity(
                txnId, "000000000023", "COLLECT", "anjali374@paytm", "keerthi335@okaxis",
                amount, "Collect", TransactionType.P2P, TransactionStatus.DECLINED, null,
                BigDecimal.ZERO, now, null, null
        );

        when(collectRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(collect));
        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(txn));

        TxnLogRow row = collectService.approveCollect(57, txnId, now.plusMinutes(15));

        assertEquals("DECLINED", row.status());
        verify(accountService, never()).debitAccount(anyLong(), any());
    }

    // =========================================================================
    // 6. LIMITS & VALIDATION ENGINE INTEGRATION
    // =========================================================================

    @Test
    @DisplayName("13. Rule 8: Approval with insufficient balance fails BR-06 (U16)")
    void testApprovalWithInsufficientBalance() {
        String txnId = "TXN-BAL-FAIL";
        BigDecimal amount = new BigDecimal("5000.00");

        CollectRequestEntity collect = new CollectRequestEntity(
                txnId, "udupi@icici", "geetha308@ybl", amount, 30, true, now.plusMinutes(30), CollectStatus.PENDING
        );
        TransactionEntity txn = new TransactionEntity(
                txnId, "000000000088", "COLLECT", "geetha308@ybl", "udupi@icici",
                amount, "Collect", TransactionType.P2M, TransactionStatus.PENDING, null,
                BigDecimal.ZERO, now, null, null
        );

        when(collectRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(collect));
        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(txn));

        when(validationPipelineService.validateIdentityAndStructure(any())).thenReturn(ValidationResult.success());
        // Validation fails with BR-06
        when(validationPipelineService.validate(any())).thenReturn(ValidationResult.failure("BR-06", "U16", "Insufficient balance"));

        TxnLogRow row = collectService.approveCollect(1, txnId, now.plusMinutes(5));

        assertEquals("FAILED", row.status());
        assertEquals("BR-06", row.ruleIds());
        assertEquals(CollectStatus.FAILED, collect.getStatus());
        assertEquals(TransactionStatus.FAILED, txn.getStatus());

        verify(accountService, never()).debitAccount(anyLong(), any());
        verify(counterService, never()).incrementDaily(any(), any(), any());
    }

    @Test
    @DisplayName("14. Rule 8: Approval violating daily limit fails BR-03")
    void testApprovalViolatingDailyLimit() {
        String txnId = "TXN-DAILY-FAIL";
        BigDecimal amount = new BigDecimal("10000.00");

        CollectRequestEntity collect = new CollectRequestEntity(
                txnId, "udupi@icici", "geetha308@ybl", amount, 30, true, now.plusMinutes(30), CollectStatus.PENDING
        );
        TransactionEntity txn = new TransactionEntity(
                txnId, "000000000089", "COLLECT", "geetha308@ybl", "udupi@icici",
                amount, "Collect", TransactionType.P2M, TransactionStatus.PENDING, null,
                BigDecimal.ZERO, now, null, null
        );

        when(collectRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(collect));
        when(transactionRepository.findByTxnIdForUpdate(txnId)).thenReturn(Optional.of(txn));

        when(validationPipelineService.validateIdentityAndStructure(any())).thenReturn(ValidationResult.success());
        when(validationPipelineService.validate(any())).thenReturn(ValidationResult.failure("BR-03", "U15", "Daily limit breached"));

        TxnLogRow row = collectService.approveCollect(1, txnId, now.plusMinutes(5));

        assertEquals("FAILED", row.status());
        assertEquals("BR-03", row.ruleIds());
        assertEquals(CollectStatus.FAILED, collect.getStatus());
        verify(accountService, never()).debitAccount(anyLong(), any());
    }

    // =========================================================================
    // 7. EXACT PIN VALUE TESTS (₹1,999, ₹2,000, ₹2,001, ₹5,000)
    // =========================================================================

    @Test
    @DisplayName("15. PIN boundary: ₹1,999.00 does not require PIN flag (pin_flag=false allowed)")
    void testCollectOneThousandNineHundredNinetyNineWithoutPin() {
        String txnId = "TXN-1999";
        TxnLogRow row = collectService.initiateCollect(
                1, txnId, now, "udupi@icici", "geetha308@ybl", new BigDecimal("1999.00"), 30, false
        );
        assertEquals("PENDING", row.status());
    }

    @Test
    @DisplayName("16. PIN boundary: ₹2,001.00 strictly requires PIN flag (pin_flag=false fails BR-07)")
    void testCollectTwoThousandOneWithoutPin() {
        String txnId = "TXN-2001";
        TxnLogRow row = collectService.initiateCollect(
                1, txnId, now, "udupi@icici", "geetha308@ybl", new BigDecimal("2001.00"), 30, false
        );
        assertEquals("FAILED", row.status());
        assertEquals("BR-07", row.ruleIds());
    }

    @Test
    @DisplayName("17. PIN boundary: ₹5,000.00 strictly requires PIN flag (pin_flag=false fails BR-07)")
    void testCollectFiveThousandWithoutPin() {
        String txnId = "TXN-5000";
        TxnLogRow row = collectService.initiateCollect(
                1, txnId, now, "udupi@icici", "geetha308@ybl", new BigDecimal("5000.00"), 30, false
        );
        assertEquals("FAILED", row.status());
        assertEquals("BR-07", row.ruleIds());
    }

    // =========================================================================
    // 8. INVALID INPUTS & UNKNOWN VPAS
    // =========================================================================

    @Test
    @DisplayName("18. Zero and negative collect amounts are rejected with INVALID_AMOUNT")
    void testCollectInvalidAmounts() {
        TxnLogRow zeroRow = collectService.initiateCollect(
                1, "TXN-ZERO", now, "udupi@icici", "geetha308@ybl", BigDecimal.ZERO, 30, false
        );
        assertEquals("FAILED", zeroRow.status());
        assertEquals("INVALID_AMOUNT", zeroRow.ruleIds());

        TxnLogRow negRow = collectService.initiateCollect(
                2, "TXN-NEG", now, "udupi@icici", "geetha308@ybl", new BigDecimal("-100.00"), 30, false
        );
        assertEquals("FAILED", negRow.status());
        assertEquals("INVALID_AMOUNT", negRow.ruleIds());
    }

    @Test
    @DisplayName("19. Unknown requester or payer VPA is rejected with PAYEE_NOT_FOUND or PAYER_NOT_FOUND")
    void testCollectUnknownVpas() {
        when(vpaService.findByVpa("ghost@icici")).thenReturn(Optional.empty());

        TxnLogRow row1 = collectService.initiateCollect(
                1, "TXN-UNK-REQ", now, "ghost@icici", "geetha308@ybl", new BigDecimal("500.00"), 30, false
        );
        assertEquals("FAILED", row1.status());
        assertEquals("PAYEE_NOT_FOUND", row1.ruleIds());

        when(vpaService.findByVpa("ghost@ybl")).thenReturn(Optional.empty());

        TxnLogRow row2 = collectService.initiateCollect(
                2, "TXN-UNK-PAYER", now, "udupi@icici", "ghost@ybl", new BigDecimal("500.00"), 30, false
        );
        assertEquals("FAILED", row2.status());
        assertEquals("PAYER_NOT_FOUND", row2.ruleIds());
    }

    // =========================================================================
    // 9. EXPIRY & DECLINE BOUNDARIES (1s BEFORE, EXACT, 1s AFTER)
    // =========================================================================

    @Test
    @DisplayName("20. Approval 1s before expiry succeeds; Approval 1s after expiry fails EXPIRED")
    void testApprovalOneSecondBeforeAndAfterExpiry() {
        LocalDateTime expiresAt = now.plusMinutes(30);

        // 1s before: 12:29:59
        CollectRequestEntity collect1 = new CollectRequestEntity("TXN-1S-BEFORE", "udupi@icici", "geetha308@ybl",
                new BigDecimal("500.00"), 30, false, expiresAt, CollectStatus.PENDING);
        TransactionEntity txn1 = new TransactionEntity("TXN-1S-BEFORE", "000000000101", "COLLECT", "geetha308@ybl", "udupi@icici",
                new BigDecimal("500.00"), "Collect", TransactionType.P2M, TransactionStatus.PENDING, null, BigDecimal.ZERO, now, null, null);

        when(collectRepository.findByTxnIdForUpdate("TXN-1S-BEFORE")).thenReturn(Optional.of(collect1));
        when(transactionRepository.findByTxnIdForUpdate("TXN-1S-BEFORE")).thenReturn(Optional.of(txn1));
        when(validationPipelineService.validateIdentityAndStructure(any())).thenReturn(ValidationResult.success());
        when(validationPipelineService.validate(any())).thenReturn(ValidationResult.success());

        AccountEntity payerAcc = new AccountEntity(10L, "100000000010", "YBL001", "USR-004", null, new BigDecimal("50000.00"), "INR", 0L, now, now);
        when(accountService.getEntityForUpdate(anyLong())).thenReturn(payerAcc);
        when(accountService.debitAccount(eq(10L), any())).thenReturn(payerAcc);
        when(accountService.creditAccount(eq(20L), any())).thenReturn(payerAcc);

        TxnLogRow rowBefore = collectService.approveCollect(1, "TXN-1S-BEFORE", expiresAt.minusSeconds(1));
        assertEquals("SUCCESS", rowBefore.status());

        // 1s after: 12:30:01
        CollectRequestEntity collect2 = new CollectRequestEntity("TXN-1S-AFTER", "udupi@icici", "geetha308@ybl",
                new BigDecimal("500.00"), 30, false, expiresAt, CollectStatus.PENDING);
        TransactionEntity txn2 = new TransactionEntity("TXN-1S-AFTER", "000000000102", "COLLECT", "geetha308@ybl", "udupi@icici",
                new BigDecimal("500.00"), "Collect", TransactionType.P2M, TransactionStatus.PENDING, null, BigDecimal.ZERO, now, null, null);

        when(collectRepository.findByTxnIdForUpdate("TXN-1S-AFTER")).thenReturn(Optional.of(collect2));
        when(transactionRepository.findByTxnIdForUpdate("TXN-1S-AFTER")).thenReturn(Optional.of(txn2));

        TxnLogRow rowAfter = collectService.approveCollect(2, "TXN-1S-AFTER", expiresAt.plusSeconds(1));
        assertEquals("EXPIRED", rowAfter.status());
    }

    @Test
    @DisplayName("21. Decline after expiry is rejected (cannot decline already expired collect)")
    void testDeclineAfterExpiry() {
        LocalDateTime expiresAt = now.plusMinutes(30);

        CollectRequestEntity collect = new CollectRequestEntity("TXN-DEC-EXP", "udupi@icici", "geetha308@ybl",
                new BigDecimal("500.00"), 30, false, expiresAt, CollectStatus.PENDING);
        TransactionEntity txn = new TransactionEntity("TXN-DEC-EXP", "000000000103", "COLLECT", "geetha308@ybl", "udupi@icici",
                new BigDecimal("500.00"), "Collect", TransactionType.P2M, TransactionStatus.PENDING, null, BigDecimal.ZERO, now, null, null);

        when(collectRepository.findByTxnIdForUpdate("TXN-DEC-EXP")).thenReturn(Optional.of(collect));
        when(transactionRepository.findByTxnIdForUpdate("TXN-DEC-EXP")).thenReturn(Optional.of(txn));

        // Attempt decline at 12:35:00 (after expiry)
        TxnLogRow row = collectService.declineCollect(1, "TXN-DEC-EXP", expiresAt.plusMinutes(5));
        assertEquals("FAILED", row.status());
        assertEquals("ALREADY_EXPIRED", row.ruleIds());
    }
}

