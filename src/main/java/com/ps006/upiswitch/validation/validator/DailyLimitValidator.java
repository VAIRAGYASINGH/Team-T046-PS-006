package com.ps006.upiswitch.validation.validator;

import com.ps006.upiswitch.config.SwitchProperties;
import com.ps006.upiswitch.payment.model.TransactionType;
import com.ps006.upiswitch.validation.model.PaymentValidationContext;
import com.ps006.upiswitch.validation.model.RuleValidationResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * BR-03: Daily Cumulative Debit Amount Cap
 * - Restricts the cumulative daily debit amount for any user to ₹1,00,000.00 within a single calendar day.
 * - For verified P2M transactions, cap is ₹2,00,000.00 per regulatory specification.
 */
@Component
@Order(30)
public class DailyLimitValidator implements PaymentRuleValidator {

    public static final String RULE_ID = "BR-03";
    private static final BigDecimal DAILY_MAX = new BigDecimal("100000.00");
    private static final BigDecimal VERIFIED_P2M_MAX = new BigDecimal("200000.00");

    private final SwitchProperties switchProperties;

    public DailyLimitValidator(SwitchProperties switchProperties) {
        this.switchProperties = switchProperties;
    }

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public int getOrder() {
        return 30;
    }

    @Override
    public RuleValidationResult validate(PaymentValidationContext context) {
        BigDecimal dailyMax = (switchProperties != null && switchProperties.getLimits() != null)
                ? switchProperties.getLimits().getDailyMax()
                : DAILY_MAX;

        if (context.transactionType() == TransactionType.P2M && context.payeeMerchant() != null && context.payeeMerchant().verified()) {
            dailyMax = (switchProperties != null && switchProperties.getLimits() != null)
                    ? switchProperties.getLimits().getMerchantVerifiedMax()
                    : VERIFIED_P2M_MAX;
        }

        BigDecimal currentDebitAmount = context.payerDailyCounter() != null
                ? context.payerDailyCounter().debitAmount()
                : BigDecimal.ZERO;

        BigDecimal projectedTotal = currentDebitAmount.add(context.amount());
        if (projectedTotal.compareTo(dailyMax) > 0) {
            return RuleValidationResult.failure(RULE_ID, "U31",
                    String.format("Projected cumulative daily debits %s exceeds daily limit of %s (current: %s)",
                            projectedTotal, dailyMax, currentDebitAmount));
        }
        return RuleValidationResult.success();
    }
}
