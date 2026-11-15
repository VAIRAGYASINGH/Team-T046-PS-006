package com.ps006.upiswitch.payment;

import com.ps006.upiswitch.account.model.AccountDetails;
import com.ps006.upiswitch.config.SwitchProperties;
import com.ps006.upiswitch.counter.model.DailyCounterModel;
import com.ps006.upiswitch.merchant.model.MerchantProfile;
import com.ps006.upiswitch.payment.entity.BeneficiaryPaymentHistoryEntity;
import com.ps006.upiswitch.payment.model.TransactionType;
import com.ps006.upiswitch.user.model.UserProfile;
import com.ps006.upiswitch.validation.model.PaymentValidationContext;
import com.ps006.upiswitch.validation.model.ValidationResult;
import com.ps006.upiswitch.validation.service.ValidationPipelineService;
import com.ps006.upiswitch.validation.validator.*;
import com.ps006.upiswitch.vpa.model.OwnerType;
import com.ps006.upiswitch.vpa.model.VpaDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PaymentRuleEngineComprehensiveTest {

    private ValidationPipelineService pipelineService;
    private SwitchProperties properties;

    private VpaDetails defaultPayerVpa;
    private VpaDetails defaultPayeeUserVpa;
    private VpaDetails verifiedMerchantVpa;
    private VpaDetails unverifiedMerchantVpa;
    private MerchantProfile verifiedMerchant;
    private MerchantProfile unverifiedMerchant;
    private AccountDetails fundedAccount;
    private UserProfile defaultUser;

    @BeforeEach
    void setUp() {
        properties = new SwitchProperties();
        List<PaymentRuleValidator> validators = List.of(
                new PerTransactionLimitValidator(properties),
                new NewVpaCoolingValidator(properties),
                new DailyLimitValidator(properties),
                new VelocityLimitValidator(properties),
                new BeneficiaryCoolingValidator(properties),
                new BalanceValidator()
        );
        pipelineService = new ValidationPipelineService(validators);

        LocalDateTime matureDate = LocalDateTime.of(2026, 8, 1, 10, 0, 0);

        defaultPayerVpa = new VpaDetails("payer@okaxis", 101L, OwnerType.USER, "USR-001", matureDate, true);
        defaultPayeeUserVpa = new VpaDetails("payee@oksbi", 102L, OwnerType.USER, "USR-002", matureDate, true);
        verifiedMerchantVpa = new VpaDetails("bengaluru@icici", 201L, OwnerType.MERCHANT, "MER-001", matureDate, true);
        unverifiedMerchantVpa = new VpaDetails("unverified@icici", 202L, OwnerType.MERCHANT, "MER-002", matureDate, true);

        verifiedMerchant = new MerchantProfile("MER-001", "Bengaluru Cafe", "5812", true, matureDate);
        unverifiedMerchant = new MerchantProfile("MER-002", "Corner Shop", "5411", false, matureDate);

        fundedAccount = new AccountDetails(
                101L, "ACC100000001", "UTIB0001", "USR-001", null,
                new BigDecimal("500000.00"), "INR", 0L, matureDate, matureDate
        );

        defaultUser = new UserProfile("USR-001", "Rohit", "9999999991", null, matureDate);
    }

    // =========================================================================
    // 1. TRANSACTION CAP BOUNDARIES (BR-01)
    // =========================================================================

    @Test
    @DisplayName("BR-01: P2P payment exactly ₹1,00,000.00 passes")
    void testP2pExactlyOneLakhPasses() {
        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-CAP-01", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("100000.00"), LocalDateTime.of(2026, 10, 1, 12, 0, 0),
                TransactionType.P2P, defaultPayerVpa, defaultPayeeUserVpa,
                defaultUser, null, fundedAccount, null, null
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertTrue(res.valid(), "₹1,00,000.00 exactly must pass P2P cap");
    }

    @Test
    @DisplayName("BR-01: P2P payment ₹1,00,000.01 fails BR-01")
    void testP2pOneLakhAndOnePaisaFails() {
        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-CAP-02", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("100000.01"), LocalDateTime.of(2026, 10, 1, 12, 0, 0),
                TransactionType.P2P, defaultPayerVpa, defaultPayeeUserVpa,
                defaultUser, null, fundedAccount, null, null
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertFalse(res.valid());
        assertEquals("BR-01", res.ruleId());
        assertEquals("AM01", res.errorCode());
    }

    @Test
    @DisplayName("BR-01: Verified P2M payment exactly ₹2,00,000.00 passes")
    void testVerifiedP2mExactlyTwoLakhsPasses() {
        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-CAP-03", defaultPayerVpa.vpa(), verifiedMerchantVpa.vpa(),
                new BigDecimal("200000.00"), LocalDateTime.of(2026, 10, 1, 12, 0, 0),
                TransactionType.P2M, defaultPayerVpa, verifiedMerchantVpa,
                defaultUser, verifiedMerchant, fundedAccount, null, null
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertTrue(res.valid(), "₹2,00,000.00 exactly must pass verified merchant cap");
    }

    @Test
    @DisplayName("BR-01: Verified P2M payment ₹2,00,000.01 fails BR-01")
    void testVerifiedP2mTwoLakhsAndOnePaisaFails() {
        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-CAP-04", defaultPayerVpa.vpa(), verifiedMerchantVpa.vpa(),
                new BigDecimal("200000.01"), LocalDateTime.of(2026, 10, 1, 12, 0, 0),
                TransactionType.P2M, defaultPayerVpa, verifiedMerchantVpa,
                defaultUser, verifiedMerchant, fundedAccount, null, null
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertFalse(res.valid());
        assertEquals("BR-01", res.ruleId());
    }

    @Test
    @DisplayName("BR-01: Unverified P2M payment exactly ₹1,00,000.00 passes")
    void testUnverifiedP2mExactlyOneLakhPasses() {
        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-CAP-05", defaultPayerVpa.vpa(), unverifiedMerchantVpa.vpa(),
                new BigDecimal("100000.00"), LocalDateTime.of(2026, 10, 1, 12, 0, 0),
                TransactionType.P2M, defaultPayerVpa, unverifiedMerchantVpa,
                defaultUser, unverifiedMerchant, fundedAccount, null, null
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertTrue(res.valid(), "₹1,00,000.00 exactly must pass unverified merchant cap");
    }

    @Test
    @DisplayName("BR-01: Unverified P2M payment ₹1,00,000.01 fails BR-01")
    void testUnverifiedP2mOneLakhAndOnePaisaFails() {
        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-CAP-06", defaultPayerVpa.vpa(), unverifiedMerchantVpa.vpa(),
                new BigDecimal("100000.01"), LocalDateTime.of(2026, 10, 1, 12, 0, 0),
                TransactionType.P2M, defaultPayerVpa, unverifiedMerchantVpa,
                defaultUser, unverifiedMerchant, fundedAccount, null, null
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertFalse(res.valid());
        assertEquals("BR-01", res.ruleId());
    }

    // =========================================================================
    // 2. NEW VPA COOLING BOUNDARIES (BR-02)
    // =========================================================================

    @Test
    @DisplayName("BR-02: New VPA exactly ₹5,000.00 within cooling period passes")
    void testNewVpaExactlyFiveThousandPasses() {
        LocalDateTime vpaCreated = LocalDateTime.of(2026, 10, 4, 10, 0, 0);
        LocalDateTime txnTime = LocalDateTime.of(2026, 10, 4, 12, 0, 0); // 2 hours old
        VpaDetails newVpa = new VpaDetails("newuser@okaxis", 103L, OwnerType.USER, "USR-003", vpaCreated, true);

        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-COOL-01", newVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("5000.00"), txnTime, TransactionType.P2P,
                newVpa, defaultPayeeUserVpa, defaultUser, null, fundedAccount, null, null
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertTrue(res.valid(), "₹5,000.00 exactly within cooling period must pass");
    }

    @Test
    @DisplayName("BR-02: New VPA ₹5,000.01 within cooling period fails BR-02")
    void testNewVpaFiveThousandAndOnePaisaFails() {
        LocalDateTime vpaCreated = LocalDateTime.of(2026, 10, 4, 10, 0, 0);
        LocalDateTime txnTime = LocalDateTime.of(2026, 10, 4, 12, 0, 0); // 2 hours old
        VpaDetails newVpa = new VpaDetails("newuser@okaxis", 103L, OwnerType.USER, "USR-003", vpaCreated, true);

        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-COOL-02", newVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("5000.01"), txnTime, TransactionType.P2P,
                newVpa, defaultPayeeUserVpa, defaultUser, null, fundedAccount, null, null
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertFalse(res.valid());
        assertEquals("BR-02", res.ruleId());
        assertEquals("U29", res.errorCode());
    }

    @Test
    @DisplayName("BR-02: VPA created 23h 59m 59s ago is still in cooling; ₹6,000 fails BR-02")
    void testVpaAge23Hours59Minutes59SecondsFails() {
        LocalDateTime vpaCreated = LocalDateTime.of(2026, 10, 4, 10, 0, 0);
        LocalDateTime txnTime = vpaCreated.plusHours(23).plusMinutes(59).plusSeconds(59);
        VpaDetails newVpa = new VpaDetails("newuser@okaxis", 103L, OwnerType.USER, "USR-003", vpaCreated, true);

        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-COOL-03", newVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("6000.00"), txnTime, TransactionType.P2P,
                newVpa, defaultPayeeUserVpa, defaultUser, null, fundedAccount, null, null
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertFalse(res.valid());
        assertEquals("BR-02", res.ruleId(), "23h 59m 59s is strictly within cooling window (< 24h)");
    }

    @Test
    @DisplayName("BR-02: VPA created exactly 24 hours ago (86,400s) has cooling expired; ₹6,000 passes")
    void testVpaAgeExactly24HoursPasses() {
        LocalDateTime vpaCreated = LocalDateTime.of(2026, 10, 4, 10, 0, 0);
        LocalDateTime txnTime = vpaCreated.plusHours(24); // Exactly 24h
        VpaDetails matureVpa = new VpaDetails("mature@okaxis", 103L, OwnerType.USER, "USR-003", vpaCreated, true);

        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-COOL-04", matureVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("6000.00"), txnTime, TransactionType.P2P,
                matureVpa, defaultPayeeUserVpa, defaultUser, null, fundedAccount, null, null
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertTrue(res.valid(), "At exactly 24 hours, cooling is expired; ₹6,000 must pass");
    }

    // =========================================================================
    // 3. DAILY CUMULATIVE CAP BOUNDARIES (BR-03)
    // =========================================================================

    @Test
    @DisplayName("BR-03: Daily cumulative amount reaching exactly ₹1,00,000.00 passes")
    void testDailyCapReachingExactlyOneLakhPasses() {
        LocalDate today = LocalDate.of(2026, 10, 1);
        DailyCounterModel counter = new DailyCounterModel(1L, "USR-001", today, 5, new BigDecimal("90000.00"));

        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-DAILY-01", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("10000.00"), LocalDateTime.of(2026, 10, 1, 23, 59, 59),
                TransactionType.P2P, defaultPayerVpa, defaultPayeeUserVpa,
                defaultUser, null, fundedAccount, counter, null
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertTrue(res.valid(), "₹90,000 + ₹10,000 = exactly ₹1,00,000 must pass");
    }

    @Test
    @DisplayName("BR-03: Daily cumulative amount reaching ₹1,00,000.01 fails BR-03")
    void testDailyCapExceedingByOnePaisaFails() {
        LocalDate today = LocalDate.of(2026, 10, 1);
        DailyCounterModel counter = new DailyCounterModel(1L, "USR-001", today, 5, new BigDecimal("90000.00"));

        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-DAILY-02", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("10000.01"), LocalDateTime.of(2026, 10, 1, 23, 59, 59),
                TransactionType.P2P, defaultPayerVpa, defaultPayeeUserVpa,
                defaultUser, null, fundedAccount, counter, null
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertFalse(res.valid());
        assertEquals("BR-03", res.ruleId());
        assertEquals("U31", res.errorCode());
    }

    // =========================================================================
    // 4. VELOCITY LIMIT BOUNDARIES (BR-04)
    // =========================================================================

    @Test
    @DisplayName("BR-04: 20th debit of the day passes")
    void testTwentiethDebitPasses() {
        LocalDate today = LocalDate.of(2026, 10, 1);
        DailyCounterModel counter = new DailyCounterModel(1L, "USR-001", today, 19, new BigDecimal("10000.00"));

        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-VEL-01", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("100.00"), LocalDateTime.of(2026, 10, 1, 15, 0, 0),
                TransactionType.P2P, defaultPayerVpa, defaultPayeeUserVpa,
                defaultUser, null, fundedAccount, counter, null
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertTrue(res.valid(), "19 prior debits -> 20th debit must pass");
    }

    @Test
    @DisplayName("BR-04: 21st debit of the day fails BR-04 with error code U30")
    void testTwentyFirstDebitFailsWithU30() {
        LocalDate today = LocalDate.of(2026, 10, 1);
        DailyCounterModel counter = new DailyCounterModel(1L, "USR-001", today, 20, new BigDecimal("10000.00"));

        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-VEL-02", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("100.00"), LocalDateTime.of(2026, 10, 1, 16, 0, 0),
                TransactionType.P2P, defaultPayerVpa, defaultPayeeUserVpa,
                defaultUser, null, fundedAccount, counter, null
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertFalse(res.valid());
        assertEquals("BR-04", res.ruleId());
        assertEquals("U30", res.errorCode(), "Must return NPCI U30 error code on 21st debit");
    }

    // =========================================================================
    // 5. BENEFICIARY COOLING BOUNDARIES (BR-05)
    // =========================================================================

    @Test
    @DisplayName("BR-05: First payment to new beneficiary exactly ₹5,000.00 passes")
    void testFirstPaymentToNewBeneficiaryExactlyFiveThousandPasses() {
        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-BENE-01", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("5000.00"), LocalDateTime.of(2026, 10, 6, 11, 0, 0),
                TransactionType.P2P, defaultPayerVpa, defaultPayeeUserVpa,
                defaultUser, null, fundedAccount, null, null // null history -> first payment
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertTrue(res.valid(), "First payment of ₹5,000 exactly must pass");
    }

    @Test
    @DisplayName("BR-05: First payment to new beneficiary ₹5,000.01 fails BR-05")
    void testFirstPaymentToNewBeneficiaryFiveThousandAndOnePaisaFails() {
        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-BENE-02", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("5000.01"), LocalDateTime.of(2026, 10, 6, 11, 0, 0),
                TransactionType.P2P, defaultPayerVpa, defaultPayeeUserVpa,
                defaultUser, null, fundedAccount, null, null
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertFalse(res.valid());
        assertEquals("BR-05", res.ruleId());
        assertEquals("U28", res.errorCode());
    }

    @Test
    @DisplayName("BR-05: Third payment within 24 hours of first payment capped at ₹5,000; ₹7,000 fails")
    void testThirdPaymentWithin24HoursCapBreachFails() {
        LocalDateTime t1 = LocalDateTime.of(2026, 10, 6, 11, 0, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 10, 6, 13, 0, 0); // 2 hours after t1
        BeneficiaryPaymentHistoryEntity history = new BeneficiaryPaymentHistoryEntity(
                defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(), t1, 2, t1.plusHours(1)
        );

        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-BENE-03", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("7000.00"), t3, TransactionType.P2P,
                defaultPayerVpa, defaultPayeeUserVpa, defaultUser, null, fundedAccount, null, history
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertFalse(res.valid());
        assertEquals("BR-05", res.ruleId());
    }

    @Test
    @DisplayName("BR-05: Payment at exactly 24 hours after first payment has cooling expired; ₹7,000 passes")
    void testBeneficiaryPaymentAtExactly24HoursPasses() {
        LocalDateTime t1 = LocalDateTime.of(2026, 10, 6, 11, 0, 0);
        LocalDateTime t4 = t1.plusHours(24); // Exactly 24 hours later
        BeneficiaryPaymentHistoryEntity history = new BeneficiaryPaymentHistoryEntity(
                defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(), t1, 2, t1.plusHours(2)
        );

        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-BENE-04", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("7000.00"), t4, TransactionType.P2P,
                defaultPayerVpa, defaultPayeeUserVpa, defaultUser, null, fundedAccount, null, history
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertTrue(res.valid(), "At exactly 24 hours, beneficiary cooling is expired; ₹7,000 must pass");
    }

    // =========================================================================
    // 6. BALANCE VALIDATION EXECUTED LAST & NON-SHORT-CIRCUITING
    // =========================================================================

    @Test
    @DisplayName("BR-06: Balance check runs last; both BR-01 and BR-06 collected; BR-01 reported first")
    void testBalanceRunsLastAndRegulatoryRuleReportedFirst() {
        // Payer has ₹1,500 balance, attempts ₹1,20,000 payment.
        // Breaches both P2P cap (BR-01) and balance (BR-06).
        AccountDetails lowBalAccount = new AccountDetails(
                101L, "ACC100000001", "UTIB0001", "USR-001", null,
                new BigDecimal("1500.00"), "INR", 0L, LocalDateTime.now(), LocalDateTime.now()
        );

        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-ORDER-01", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("120000.00"), LocalDateTime.of(2026, 10, 24, 10, 0, 0),
                TransactionType.P2P, defaultPayerVpa, defaultPayeeUserVpa,
                defaultUser, null, lowBalAccount, null, null
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertFalse(res.valid());

        // Invariant: Both failed rules are collected without short-circuiting
        assertEquals(List.of("BR-01", "BR-06"), res.failedRuleIds(),
                "Must evaluate without short-circuiting and collect both BR-01 and BR-06");

        // Invariant: Primary reported rule ID is the regulatory rule BR-01, NOT BR-06
        assertEquals("BR-01", res.ruleId(), "Primary reported rule must be BR-01");
    }

    // =========================================================================
    // 7. EXACT ONE RUPEE BOUNDARY TESTS FOR ALL LIMITS
    // =========================================================================

    @Test
    @DisplayName("BR-01: One rupee below limits (₹99,999.00 P2P, ₹1,99,999.00 Verified P2M) passes")
    void testOneRupeeBelowCaps() {
        // P2P: ₹99,999.00
        PaymentValidationContext ctxP2p = new PaymentValidationContext(
                "TXN-ONE-01", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("99999.00"), LocalDateTime.of(2026, 10, 1, 12, 0, 0),
                TransactionType.P2P, defaultPayerVpa, defaultPayeeUserVpa,
                defaultUser, null, fundedAccount, null, null
        );
        assertTrue(pipelineService.validate(ctxP2p).valid(), "₹99,999.00 must pass P2P cap");

        // Verified P2M: ₹1,99,999.00
        PaymentValidationContext ctxP2m = new PaymentValidationContext(
                "TXN-ONE-02", defaultPayerVpa.vpa(), verifiedMerchantVpa.vpa(),
                new BigDecimal("199999.00"), LocalDateTime.of(2026, 10, 1, 12, 0, 0),
                TransactionType.P2M, defaultPayerVpa, verifiedMerchantVpa,
                defaultUser, verifiedMerchant, fundedAccount, null, null
        );
        assertTrue(pipelineService.validate(ctxP2m).valid(), "₹1,99,999.00 must pass verified merchant cap");

        // Unverified P2M: ₹99,999.00
        PaymentValidationContext ctxUnv = new PaymentValidationContext(
                "TXN-ONE-03", defaultPayerVpa.vpa(), unverifiedMerchantVpa.vpa(),
                new BigDecimal("99999.00"), LocalDateTime.of(2026, 10, 1, 12, 0, 0),
                TransactionType.P2M, defaultPayerVpa, unverifiedMerchantVpa,
                defaultUser, unverifiedMerchant, fundedAccount, null, null
        );
        assertTrue(pipelineService.validate(ctxUnv).valid(), "₹99,999.00 must pass unverified merchant cap");
    }

    @Test
    @DisplayName("BR-01: One rupee above limits (₹1,00,001.00 P2P, ₹2,00,001.00 Verified P2M) fails BR-01")
    void testOneRupeeAboveCaps() {
        // P2P: ₹1,00,001.00
        PaymentValidationContext ctxP2p = new PaymentValidationContext(
                "TXN-ONE-04", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("100001.00"), LocalDateTime.of(2026, 10, 1, 12, 0, 0),
                TransactionType.P2P, defaultPayerVpa, defaultPayeeUserVpa,
                defaultUser, null, fundedAccount, null, null
        );
        ValidationResult resP2p = pipelineService.validate(ctxP2p);
        assertFalse(resP2p.valid());
        assertEquals("BR-01", resP2p.ruleId());

        // Verified P2M: ₹2,00,001.00
        PaymentValidationContext ctxP2m = new PaymentValidationContext(
                "TXN-ONE-05", defaultPayerVpa.vpa(), verifiedMerchantVpa.vpa(),
                new BigDecimal("200001.00"), LocalDateTime.of(2026, 10, 1, 12, 0, 0),
                TransactionType.P2M, defaultPayerVpa, verifiedMerchantVpa,
                defaultUser, verifiedMerchant, fundedAccount, null, null
        );
        ValidationResult resP2m = pipelineService.validate(ctxP2m);
        assertFalse(resP2m.valid());
        assertEquals("BR-01", resP2m.ruleId());
    }

    @Test
    @DisplayName("BR-02 & BR-05: One rupee below and above cooling limit (₹4,999.00 vs ₹5,001.00)")
    void testOneRupeeCoolingBoundaries() {
        LocalDateTime vpaCreated = LocalDateTime.of(2026, 10, 4, 10, 0, 0);
        LocalDateTime txnTime = LocalDateTime.of(2026, 10, 4, 12, 0, 0);
        VpaDetails newVpa = new VpaDetails("newuser@okaxis", 103L, OwnerType.USER, "USR-003", vpaCreated, true);

        // One rupee below: ₹4,999.00
        PaymentValidationContext ctxBelow = new PaymentValidationContext(
                "TXN-COOL-BELOW", newVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("4999.00"), txnTime, TransactionType.P2P,
                newVpa, defaultPayeeUserVpa, defaultUser, null, fundedAccount, null, null
        );
        assertTrue(pipelineService.validate(ctxBelow).valid(), "₹4,999.00 must pass new VPA cooling");

        // One rupee above: ₹5,001.00
        PaymentValidationContext ctxAbove = new PaymentValidationContext(
                "TXN-COOL-ABOVE", newVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("5001.00"), txnTime, TransactionType.P2P,
                newVpa, defaultPayeeUserVpa, defaultUser, null, fundedAccount, null, null
        );
        ValidationResult resAbove = pipelineService.validate(ctxAbove);
        assertFalse(resAbove.valid());
        assertEquals("BR-02", resAbove.ruleId());
    }

    // =========================================================================
    // 8. DAILY USAGE AND MIDNIGHT ROLLOVER BOUNDARIES
    // =========================================================================

    @Test
    @DisplayName("BR-03: Daily usage ₹99,999 followed by ₹1 passes; daily usage ₹1,00,000 followed by ₹1 fails")
    void testDailyUsageStepByStep() {
        LocalDate today = LocalDate.of(2026, 10, 1);

        // State A: Daily usage = ₹99,999.00. Payment of ₹1.00 brings total to ₹1,00,000.00 -> PASS
        DailyCounterModel counter99k = new DailyCounterModel(1L, "USR-001", today, 3, new BigDecimal("99999.00"));
        PaymentValidationContext ctxPass = new PaymentValidationContext(
                "TXN-STEP-01", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("1.00"), LocalDateTime.of(2026, 10, 1, 15, 0, 0),
                TransactionType.P2P, defaultPayerVpa, defaultPayeeUserVpa,
                defaultUser, null, fundedAccount, counter99k, null
        );
        assertTrue(pipelineService.validate(ctxPass).valid(), "₹99,999.00 + ₹1.00 = ₹1,00,000.00 exactly must pass");

        // State B: Daily usage = ₹1,00,000.00. Payment of ₹1.00 brings total to ₹1,00,001.00 -> FAIL BR-03
        DailyCounterModel counter100k = new DailyCounterModel(1L, "USR-001", today, 4, new BigDecimal("100000.00"));
        PaymentValidationContext ctxFail = new PaymentValidationContext(
                "TXN-STEP-02", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("1.00"), LocalDateTime.of(2026, 10, 1, 15, 30, 0),
                TransactionType.P2P, defaultPayerVpa, defaultPayeeUserVpa,
                defaultUser, null, fundedAccount, counter100k, null
        );
        ValidationResult resFail = pipelineService.validate(ctxFail);
        assertFalse(resFail.valid());
        assertEquals("BR-03", resFail.ruleId());
    }

    @Test
    @DisplayName("BR-03: Midnight rollover resets daily counter: payment at 23:59:59 uses old day, payment at 00:00:00 starts fresh day")
    void testMidnightRollover() {
        LocalDate day1 = LocalDate.of(2026, 10, 1);
        LocalDate day2 = LocalDate.of(2026, 10, 2);

        // Day 1 at 23:59:59 has ₹90,000 existing debits. Payment of ₹10,000 passes (total ₹100,000).
        DailyCounterModel counterDay1 = new DailyCounterModel(1L, "USR-001", day1, 5, new BigDecimal("90000.00"));
        PaymentValidationContext ctxMidnight1 = new PaymentValidationContext(
                "TXN-MID-01", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("10000.00"), LocalDateTime.of(2026, 10, 1, 23, 59, 59),
                TransactionType.P2P, defaultPayerVpa, defaultPayeeUserVpa,
                defaultUser, null, fundedAccount, counterDay1, null
        );
        assertTrue(pipelineService.validate(ctxMidnight1).valid(), "Day 1 payment at 23:59:59 must pass");

        // Day 2 at 00:00:00: fresh calendar date, counter resets to 0.00! Payment of ₹20,000 passes.
        DailyCounterModel counterDay2 = new DailyCounterModel(2L, "USR-001", day2, 0, BigDecimal.ZERO);
        PaymentValidationContext ctxMidnight2 = new PaymentValidationContext(
                "TXN-MID-02", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("20000.00"), LocalDateTime.of(2026, 10, 2, 0, 0, 0),
                TransactionType.P2P, defaultPayerVpa, defaultPayeeUserVpa,
                defaultUser, null, fundedAccount, counterDay2, null
        );
        assertTrue(pipelineService.validate(ctxMidnight2).valid(), "Day 2 payment at 00:00:00 must start fresh and pass");
    }

    @Test
    @DisplayName("BR-03: Mixed P2P and P2M transactions share the same daily ₹1,00,000 cap")
    void testMixedP2pAndP2mShareDailyCap() {
        LocalDate today = LocalDate.of(2026, 10, 1);

        // User already spent ₹60,000 on P2P. Attempts ₹45,000 to Verified Merchant.
        // Even though Verified Merchant per-txn cap is ₹2,00,000, user's daily total reaches ₹1,05,000 -> FAILS BR-03!
        DailyCounterModel counter = new DailyCounterModel(1L, "USR-001", today, 2, new BigDecimal("60000.00"));
        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-MIX-01", defaultPayerVpa.vpa(), verifiedMerchantVpa.vpa(),
                new BigDecimal("45000.00"), LocalDateTime.of(2026, 10, 1, 14, 0, 0),
                TransactionType.P2M, defaultPayerVpa, verifiedMerchantVpa,
                defaultUser, verifiedMerchant, fundedAccount, counter, null
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertFalse(res.valid());
        assertEquals("BR-03", res.ruleId(), "Daily cap BR-03 must restrict mixed P2M payments");
    }

    // =========================================================================
    // 9. MULTIPLE SIMULTANEOUS RULE FAILURES & ORDERED ACCUMULATION
    // =========================================================================

    @Test
    @DisplayName("Ordering: Velocity (BR-04) and Daily Cap (BR-03) both fail -> collected in order [BR-03, BR-04]")
    void testVelocityAndDailyCapBothFail() {
        LocalDate today = LocalDate.of(2026, 10, 1);
        // User already has 20 debits and ₹95,000 daily debit amount.
        // Payment of ₹10,000 breaches Daily Cap (₹105,000 > ₹100,000) and Velocity (21st debit).
        DailyCounterModel counter = new DailyCounterModel(1L, "USR-001", today, 20, new BigDecimal("95000.00"));

        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-MULTI-01", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("10000.00"), LocalDateTime.of(2026, 10, 1, 16, 0, 0),
                TransactionType.P2P, defaultPayerVpa, defaultPayeeUserVpa,
                defaultUser, null, fundedAccount, counter, null
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertFalse(res.valid());
        assertEquals(List.of("BR-03", "BR-04"), res.failedRuleIds(),
                "Deterministic pipeline order: BR-03 (Daily) precedes BR-04 (Velocity)");
        assertEquals("BR-03", res.ruleId(), "Primary reported rule must be BR-03");
    }

    @Test
    @DisplayName("Ordering: New VPA Cooling (BR-02) and Daily Cap (BR-03) both fail -> collected in order [BR-02, BR-03]")
    void testNewVpaAndDailyCapBothFail() {
        LocalDateTime vpaCreated = LocalDateTime.of(2026, 10, 4, 10, 0, 0);
        LocalDateTime txnTime = LocalDateTime.of(2026, 10, 4, 12, 0, 0);
        VpaDetails newVpa = new VpaDetails("newuser@okaxis", 103L, OwnerType.USER, "USR-003", vpaCreated, true);

        // Daily counter has ₹95,000. New VPA attempts ₹10,000.
        // Breaches BR-02 (₹10,000 > ₹5,000) and BR-03 (₹105,000 > ₹100,000).
        DailyCounterModel counter = new DailyCounterModel(1L, "USR-003", txnTime.toLocalDate(), 5, new BigDecimal("95000.00"));

        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-MULTI-02", newVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("10000.00"), txnTime, TransactionType.P2P,
                newVpa, defaultPayeeUserVpa, defaultUser, null, fundedAccount, counter, null
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertFalse(res.valid());
        assertEquals(List.of("BR-02", "BR-03"), res.failedRuleIds(),
                "Deterministic pipeline order: BR-02 (New VPA) precedes BR-03 (Daily)");
        assertEquals("BR-02", res.ruleId(), "Primary reported rule must be BR-02");
    }

    @Test
    @DisplayName("Ordering: Velocity (BR-04) and Insufficient Balance (BR-06) both fail -> [BR-04, BR-06]")
    void testVelocityAndBalanceBothFail() {
        LocalDate today = LocalDate.of(2026, 10, 1);
        AccountDetails lowBalAccount = new AccountDetails(
                101L, "ACC1", "IFSC1", "USR-001", null,
                new BigDecimal("50.00"), "INR", 0L, LocalDateTime.now(), LocalDateTime.now()
        );
        DailyCounterModel counter = new DailyCounterModel(1L, "USR-001", today, 20, new BigDecimal("10000.00"));

        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-MULTI-03", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("100.00"), LocalDateTime.of(2026, 10, 1, 16, 0, 0),
                TransactionType.P2P, defaultPayerVpa, defaultPayeeUserVpa,
                defaultUser, null, lowBalAccount, counter, null
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertFalse(res.valid());
        assertEquals(List.of("BR-04", "BR-06"), res.failedRuleIds(),
                "Deterministic order: BR-04 (Velocity) precedes BR-06 (Balance)");
        assertEquals("BR-04", res.ruleId(), "Regulatory rule BR-04 must be reported, not balance BR-06");
    }

    // =========================================================================
    // 10. BENEFICIARY COOLING EXTENDED CASES
    // =========================================================================

    @Test
    @DisplayName("BR-05: 4th payment within cooling period passes because first 3 cooling limit completed")
    void testFourthPaymentWithinCoolingPeriodPasses() {
        LocalDateTime t1 = LocalDateTime.of(2026, 10, 6, 11, 0, 0);
        LocalDateTime t4 = LocalDateTime.of(2026, 10, 6, 14, 0, 0); // 3 hours after t1
        // Already completed 3 payments!
        BeneficiaryPaymentHistoryEntity history = new BeneficiaryPaymentHistoryEntity(
                defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(), t1, 3, t1.plusHours(2)
        );

        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-BENE-05", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("8000.00"), t4, TransactionType.P2P,
                defaultPayerVpa, defaultPayeeUserVpa, defaultUser, null, fundedAccount, null, history
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertTrue(res.valid(), "4th payment within 24h must pass standard limit (first 3 payments cooling completed)");
    }

    @Test
    @DisplayName("BR-05: Different remitters paying same beneficiary have independent cooling histories")
    void testDifferentRemittersIndependentCooling() {
        LocalDateTime t1 = LocalDateTime.of(2026, 10, 6, 11, 0, 0);
        // Remitter A already paid Beneficiary X twice
        BeneficiaryPaymentHistoryEntity historyRemitterA = new BeneficiaryPaymentHistoryEntity(
                "remitterA@okaxis", defaultPayeeUserVpa.vpa(), t1, 2, t1.plusHours(1)
        );

        // Remitter B pays Beneficiary X for the first time with ₹5,000 -> PASS
        VpaDetails remitterBVpa = new VpaDetails("remitterB@okaxis", 104L, OwnerType.USER, "USR-004", t1.minusMonths(2), true);
        PaymentValidationContext ctxRemitterB = new PaymentValidationContext(
                "TXN-BENE-06", remitterBVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("5000.00"), t1, TransactionType.P2P,
                remitterBVpa, defaultPayeeUserVpa, defaultUser, null, fundedAccount, null, null // null history for B!
        );
        assertTrue(pipelineService.validate(ctxRemitterB).valid(), "Remitter B paying for the first time must pass");
    }

    // =========================================================================
    // 11. BALANCE EXACT AND BOUNDARY TESTS
    // =========================================================================

    @Test
    @DisplayName("BR-06: Payment exactly equal to available balance passes")
    void testExactBalancePaymentPasses() {
        AccountDetails exactAccount = new AccountDetails(
                101L, "ACC1", "IFSC1", "USR-001", null,
                new BigDecimal("1500.00"), "INR", 0L, LocalDateTime.now(), LocalDateTime.now()
        );

        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-EXACT-01", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("1500.00"), LocalDateTime.of(2026, 10, 1, 12, 0, 0),
                TransactionType.P2P, defaultPayerVpa, defaultPayeeUserVpa,
                defaultUser, null, exactAccount, null, null
        );

        assertTrue(pipelineService.validate(ctx).valid(), "Payment exactly equal to available balance must pass");
    }

    @Test
    @DisplayName("BR-06: Payment one rupee above available balance fails BR-06")
    void testOneRupeeAboveBalanceFails() {
        AccountDetails exactAccount = new AccountDetails(
                101L, "ACC1", "IFSC1", "USR-001", null,
                new BigDecimal("1500.00"), "INR", 0L, LocalDateTime.now(), LocalDateTime.now()
        );

        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-EXACT-02", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("1501.00"), LocalDateTime.of(2026, 10, 1, 12, 0, 0),
                TransactionType.P2P, defaultPayerVpa, defaultPayeeUserVpa,
                defaultUser, null, exactAccount, null, null
        );

        ValidationResult res = pipelineService.validate(ctx);
        assertFalse(res.valid());
        assertEquals("BR-06", res.ruleId());
        assertEquals("U16", res.errorCode());
    }

    // =========================================================================
    // 12. STRUCTURAL AND IDENTITY VALIDATION TESTS
    // =========================================================================

    @Test
    @DisplayName("Structural: Self-transfer (payer VPA equals payee VPA) is rejected")
    void testSelfTransferRejected() {
        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-SELF-01", defaultPayerVpa.vpa(), defaultPayerVpa.vpa(),
                new BigDecimal("500.00"), LocalDateTime.of(2026, 10, 1, 12, 0, 0),
                TransactionType.P2P, defaultPayerVpa, defaultPayerVpa,
                defaultUser, null, fundedAccount, null, null
        );

        ValidationResult res = pipelineService.validateIdentityAndStructure(ctx);
        assertFalse(res.valid());
        assertEquals("SELF_TRANSFER_NOT_ALLOWED", res.ruleId());
    }

    @Test
    @DisplayName("Structural: Non-positive amounts (₹0.00 and -₹50.00) are rejected")
    void testNonPositiveAmountsRejected() {
        // Zero amount
        PaymentValidationContext ctxZero = new PaymentValidationContext(
                "TXN-ZERO-01", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                BigDecimal.ZERO, LocalDateTime.of(2026, 10, 1, 12, 0, 0),
                TransactionType.P2P, defaultPayerVpa, defaultPayeeUserVpa,
                defaultUser, null, fundedAccount, null, null
        );
        ValidationResult resZero = pipelineService.validateIdentityAndStructure(ctxZero);
        assertFalse(resZero.valid());
        assertEquals("INVALID_AMOUNT", resZero.ruleId());

        // Negative amount
        PaymentValidationContext ctxNeg = new PaymentValidationContext(
                "TXN-NEG-01", defaultPayerVpa.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("-50.00"), LocalDateTime.of(2026, 10, 1, 12, 0, 0),
                TransactionType.P2P, defaultPayerVpa, defaultPayeeUserVpa,
                defaultUser, null, fundedAccount, null, null
        );
        ValidationResult resNeg = pipelineService.validateIdentityAndStructure(ctxNeg);
        assertFalse(resNeg.valid());
        assertEquals("INVALID_AMOUNT", resNeg.ruleId());
    }

    @Test
    @DisplayName("Structural: Inactive VPA is rejected")
    void testInactiveVpaRejected() {
        VpaDetails inactivePayer = new VpaDetails("inactive@okaxis", 105L, OwnerType.USER, "USR-005", LocalDateTime.now(), false);
        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-INACT-01", inactivePayer.vpa(), defaultPayeeUserVpa.vpa(),
                new BigDecimal("500.00"), LocalDateTime.of(2026, 10, 1, 12, 0, 0),
                TransactionType.P2P, inactivePayer, defaultPayeeUserVpa,
                defaultUser, null, fundedAccount, null, null
        );

        ValidationResult res = pipelineService.validateIdentityAndStructure(ctx);
        assertFalse(res.valid());
        assertEquals("PAYER_NOT_FOUND", res.ruleId());
    }
}


