package com.ps006.upiswitch.validation.validator;

import com.ps006.upiswitch.config.SwitchProperties;
import com.ps006.upiswitch.validation.model.PaymentValidationContext;
import com.ps006.upiswitch.validation.model.RuleValidationResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * BR-04: Daily Debit Velocity Limit
 * - Maximum 20 successful debits per user per calendar day.
 * - 21st debit returns FAILED with error code U30.
 */
@Component
@Order(40)
public class VelocityLimitValidator implements PaymentRuleValidator {

    public static final String RULE_ID = "BR-04";
    public static final String ERROR_CODE = "U30";
    private static final int DEFAULT_MAX_DEBIT_COUNT = 20;

    private final SwitchProperties switchProperties;

    public VelocityLimitValidator(SwitchProperties switchProperties) {
        this.switchProperties = switchProperties;
    }

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public int getOrder() {
        return 40;
    }

    @Override
    public RuleValidationResult validate(PaymentValidationContext context) {
        int maxDailyCount = (switchProperties != null && switchProperties.getLimits() != null)
                ? switchProperties.getLimits().getDailyMaxDebitCount()
                : DEFAULT_MAX_DEBIT_COUNT;

        int currentCount = context.payerDailyCounter() != null
                ? context.payerDailyCounter().debitCount()
                : 0;

        if (currentCount >= maxDailyCount) {
            return RuleValidationResult.failure(RULE_ID, ERROR_CODE,
                    String.format("User has reached maximum daily debit count of %d (attempted %dth debit)",
                            maxDailyCount, currentCount + 1));
        }
        return RuleValidationResult.success();
    }
}
