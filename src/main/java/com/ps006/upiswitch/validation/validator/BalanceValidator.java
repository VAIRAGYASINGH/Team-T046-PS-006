package com.ps006.upiswitch.validation.validator;

import com.ps006.upiswitch.validation.model.PaymentValidationContext;
import com.ps006.upiswitch.validation.model.RuleValidationResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * BR-06: Account Balance Validator
 * - Evaluated strictly as the FINAL rule in the validation pipeline.
 * - If an earlier regulatory rule failed (e.g. BR-01 cap), the regulatory rule failure is prioritized.
 * - If all rules are evaluated without short-circuiting, both BR-01 and BR-06 are collected deterministically.
 */
@Component
@Order(60)
public class BalanceValidator implements PaymentRuleValidator {

    public static final String RULE_ID = "BR-06";
    public static final String ERROR_CODE = "U16";

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public int getOrder() {
        return 60;
    }

    @Override
    public RuleValidationResult validate(PaymentValidationContext context) {
        if (context.payerAccount() == null) {
            return RuleValidationResult.failure(RULE_ID, ERROR_CODE, "Payer account not found or unlinked");
        }

        BigDecimal balance = context.payerAccount().balance();
        if (balance.compareTo(context.amount()) < 0) {
            return RuleValidationResult.failure(RULE_ID, ERROR_CODE,
                    String.format("Available balance %s is insufficient for payment amount %s",
                            balance, context.amount()));
        }
        return RuleValidationResult.success();
    }
}
