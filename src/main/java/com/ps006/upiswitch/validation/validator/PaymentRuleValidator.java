package com.ps006.upiswitch.validation.validator;

import com.ps006.upiswitch.validation.model.PaymentValidationContext;
import com.ps006.upiswitch.validation.model.RuleValidationResult;

public interface PaymentRuleValidator {
    String getRuleId();
    int getOrder();
    RuleValidationResult validate(PaymentValidationContext context);
}
