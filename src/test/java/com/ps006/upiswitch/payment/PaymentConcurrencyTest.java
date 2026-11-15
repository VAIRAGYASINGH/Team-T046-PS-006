package com.ps006.upiswitch.payment;

import com.ps006.upiswitch.account.entity.AccountEntity;
import com.ps006.upiswitch.account.model.AccountDetails;
import com.ps006.upiswitch.account.service.AccountService;
import com.ps006.upiswitch.common.repository.ReconciliationRecordRepository;
import com.ps006.upiswitch.common.util.RrnGenerator;
import com.ps006.upiswitch.counter.model.DailyCounterModel;
import com.ps006.upiswitch.counter.service.CounterService;
import com.ps006.upiswitch.ledger.service.LedgerService;
import com.ps006.upiswitch.merchant.service.MerchantService;
import com.ps006.upiswitch.payment.entity.TransactionEntity;
import com.ps006.upiswitch.payment.model.PaymentResult;
import com.ps006.upiswitch.payment.model.TransactionStatus;
import com.ps006.upiswitch.payment.repository.BeneficiaryPaymentHistoryRepository;
import com.ps006.upiswitch.payment.repository.TransactionEventRepository;
import com.ps006.upiswitch.payment.repository.TransactionRepository;
import com.ps006.upiswitch.payment.service.PaymentService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentConcurrencyTest {

    private PaymentService paymentService;
    private AccountService accountService;
    private TransactionRepository transactionRepository;

    private final BigDecimal initialBalance = new BigDecimal("1000.00");
    private final BigDecimal paymentAmount = new BigDecimal("400.00");
    private BigDecimal currentBalance;
    private final Object balanceLock = new Object();

    @BeforeEach
    void setUp() {
        RrnGenerator.resetSequence();
        currentBalance = initialBalance;

        transactionRepository = mock(TransactionRepository.class);
        TransactionEventRepository transactionEventRepository = mock(TransactionEventRepository.class);
        BeneficiaryPaymentHistoryRepository beneficiaryPaymentHistoryRepository = mock(BeneficiaryPaymentHistoryRepository.class);
        ReconciliationRecordRepository reconciliationRecordRepository = mock(ReconciliationRecordRepository.class);
        VpaService vpaService = mock(VpaService.class);
        accountService = mock(AccountService.class);
        UserService userService = mock(UserService.class);
        MerchantService merchantService = mock(MerchantService.class);
        CounterService counterService = mock(CounterService.class);
        LedgerService ledgerService = mock(LedgerService.class);
        ValidationPipelineService validationPipelineService = mock(ValidationPipelineService.class);

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

        LocalDateTime now = LocalDateTime.of(2026, 10, 1, 10, 0, 0);
        VpaDetails payerVpa = new VpaDetails("payer@okaxis", 1L, OwnerType.USER, "USR-001", now.minusMonths(1), true);
        VpaDetails payeeVpa = new VpaDetails("payee@oksbi", 2L, OwnerType.USER, "USR-002", now.minusMonths(1), true);

        when(vpaService.findByVpa("payer@okaxis")).thenReturn(Optional.of(payerVpa));
        when(vpaService.findByVpa("payee@oksbi")).thenReturn(Optional.of(payeeVpa));
        when(userService.findById("USR-001")).thenReturn(Optional.of(new UserProfile("USR-001", "User", "9999999991", null, now.minusMonths(1))));

        when(counterService.getDailyCounter(eq("USR-001"), any(LocalDate.class)))
                .thenReturn(new DailyCounterModel(1L, "USR-001", now.toLocalDate(), 0, BigDecimal.ZERO));

        when(validationPipelineService.validateIdentityAndStructure(any())).thenReturn(ValidationResult.success());
        when(validationPipelineService.validate(any())).thenReturn(ValidationResult.success());

        // Simulate synchronized account locking for concurrency test
        when(accountService.findById(1L)).thenAnswer(inv ->
                Optional.of(new AccountDetails(1L, "ACC1", "IFSC1", "USR-001", null, currentBalance, "INR", 0L, now, now)));

        when(accountService.getEntityForUpdate(1L)).thenAnswer(inv -> {
            synchronized (balanceLock) {
                return new AccountEntity(1L, "ACC1", "IFSC1", "USR-001", null, currentBalance, "INR", 0L, now, now);
            }
        });

        when(accountService.debitAccount(eq(1L), any(BigDecimal.class))).thenAnswer(inv -> {
            BigDecimal amt = inv.getArgument(1);
            synchronized (balanceLock) {
                currentBalance = currentBalance.subtract(amt);
                return new AccountEntity(1L, "ACC1", "IFSC1", "USR-001", null, currentBalance, "INR", 0L, now, now);
            }
        });
    }

    @Test
    @DisplayName("Verify multiple simultaneous payment requests: 3 concurrent debits of ₹400 on ₹1000 balance allow exactly 2 and reject 1")
    void testMultipleSimultaneousPaymentRequests() throws InterruptedException {
        int threads = 3;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threads);

        List<PaymentResult> results = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        for (int i = 1; i <= threads; i++) {
            final String txnId = "TXN-CONCUR-" + i;
            executor.submit(() -> {
                try {
                    startGate.await(); // Synchronize all threads to fire at the exact same instant
                    PaymentResult res = paymentService.processPaymentResult(
                            txnId, LocalDateTime.of(2026, 10, 1, 10, 0, 0),
                            "payer@okaxis", "payee@oksbi", paymentAmount, "concurrent-test"
                    );
                    results.add(res);
                    if (res.status() == TransactionStatus.PENDING) {
                        successCount.incrementAndGet();
                    } else if (res.status() == TransactionStatus.FAILED) {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    fail("Concurrent thread execution threw exception: " + e.getMessage());
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown(); // Fire all 3 threads simultaneously
        boolean completed = endGate.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "All threads must finish within timeout");
        assertEquals(3, results.size());

        // Initial balance = ₹1,000. Each payment = ₹400.
        // Exactly 2 payments can succeed (2 * 400 = 800 <= 1000). Remaining balance = ₹200.
        // The 3rd payment must fail due to insufficient funds (200 < 400).
        assertEquals(2, successCount.get(), "Exactly 2 payments must succeed on ₹1,000 balance with ₹400 each");
        assertEquals(1, failureCount.get(), "The 3rd payment must fail without creating negative overdraft balance");

        synchronized (balanceLock) {
            assertEquals(new BigDecimal("200.00"), currentBalance,
                    "Final balance must be exactly ₹200.00 without underflow or race corruption");
        }
    }
}

