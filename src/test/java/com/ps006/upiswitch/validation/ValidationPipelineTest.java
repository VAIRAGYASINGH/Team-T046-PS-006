package com.ps006.upiswitch.validation;

import com.ps006.upiswitch.account.model.AccountDetails;
import com.ps006.upiswitch.config.SwitchProperties;
import com.ps006.upiswitch.counter.model.DailyCounterModel;
import com.ps006.upiswitch.payment.model.TransactionType;
import com.ps006.upiswitch.validation.model.PaymentValidationContext;
import com.ps006.upiswitch.validation.model.ValidationResult;
import com.ps006.upiswitch.validation.service.ValidationPipelineService;
import com.ps006.upiswitch.validation.validator.*;
import com.ps006.upiswitch.vpa.model.OwnerType;
import com.ps006.upiswitch.vpa.model.VpaDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ValidationPipelineTest {

    private ValidationPipelineService pipelineService;
    private SwitchProperties properties;

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
    }

    @Test
    @DisplayName("Verify BR-06 balance check is last and regulatory cap (BR-01) takes precedence in reporting")
    void testRegulatoryPrecedenceOverBalance() {
        // Arjun has ₹1,500 balance, attempts ₹120,000 payment.
        // Violates BR-01 (Cap) and BR-06 (Balance).
        // Non-short-circuiting collects BOTH, but primary rule reported must be BR-01!
        AccountDetails account = new AccountDetails(
                1L, "ACC123", "SBIN001", "USR-005", null,
                new BigDecimal("1500.00"), "INR", 0L, LocalDateTime.now(), LocalDateTime.now()
        );
        VpaDetails payerVpa = new VpaDetails("arjun@paytm", 1L, OwnerType.USER, "USR-005", LocalDateTime.now().minusDays(10), true);
        VpaDetails payeeVpa = new VpaDetails("lokesh@oksbi", 2L, OwnerType.USER, "USR-008", LocalDateTime.now().minusDays(10), true);

        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-0032", "arjun@paytm", "lokesh@oksbi", new BigDecimal("120000.00"),
                LocalDateTime.now(), TransactionType.P2P, payerVpa, payeeVpa,
                null, null, account, null, null
        );

        ValidationResult result = pipelineService.validate(ctx);
        assertFalse(result.valid());
        assertEquals("BR-01", result.ruleId(), "Primary reported rule must be BR-01 cap, not balance check");
        assertTrue(result.hasRule("BR-01"), "Must include BR-01 in failed rules");
        assertTrue(result.hasRule("BR-06"), "Must include BR-06 in failed rules due to non-short-circuiting");
        assertEquals(List.of("BR-01", "BR-06"), result.failedRuleIds(), "Deterministic order: BR-01 then BR-06");
    }

    @Test
    @DisplayName("Verify BR-06 triggers when regulatory rules pass but balance is insufficient")
    void testInsufficientBalanceFailsBr06() {
        AccountDetails account = new AccountDetails(
                1L, "ACC123", "SBIN001", "USR-001", null,
                new BigDecimal("500.00"), "INR", 0L, LocalDateTime.now(), LocalDateTime.now()
        );
        VpaDetails payerVpa = new VpaDetails("user@okaxis", 1L, OwnerType.USER, "USR-001", LocalDateTime.now().minusDays(10), true);
        VpaDetails payeeVpa = new VpaDetails("payee@oksbi", 2L, OwnerType.USER, "USR-002", LocalDateTime.now().minusDays(10), true);

        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-BAL-001", "user@okaxis", "payee@oksbi", new BigDecimal("1000.00"),
                LocalDateTime.now(), TransactionType.P2P, payerVpa, payeeVpa,
                null, null, account, null, null
        );

        ValidationResult result = pipelineService.validate(ctx);
        assertFalse(result.valid());
        assertEquals("BR-06", result.ruleId());
        assertEquals("U16", result.errorCode());
    }

    @Test
    @DisplayName("Verify BR-04 triggers with error code U30 when daily velocity reaches 20")
    void testVelocityCapFailsBr04() {
        AccountDetails account = new AccountDetails(
                1L, "ACC123", "UBIN001", "USR-002", null,
                new BigDecimal("90000.00"), "INR", 0L, LocalDateTime.now(), LocalDateTime.now()
        );
        DailyCounterModel counter = new DailyCounterModel(
                1L, "USR-002", LocalDate.now(), 20, new BigDecimal("41250.00")
        );
        VpaDetails payerVpa = new VpaDetails("harini@ibl", 1L, OwnerType.USER, "USR-002", LocalDateTime.now().minusDays(10), true);
        VpaDetails payeeVpa = new VpaDetails("hema@ibl", 2L, OwnerType.USER, "USR-010", LocalDateTime.now().minusDays(10), true);

        PaymentValidationContext ctx = new PaymentValidationContext(
                "TXN-0002", "harini@ibl", "hema@ibl", new BigDecimal("150.00"),
                LocalDateTime.now(), TransactionType.P2P, payerVpa, payeeVpa,
                null, null, account, counter, null
        );

        ValidationResult result = pipelineService.validate(ctx);
        assertFalse(result.valid());
        assertEquals("BR-04", result.ruleId());
        assertEquals("U30", result.errorCode());
    }
}
