package com.ps006.upiswitch.validation.service;

import com.ps006.upiswitch.validation.model.PaymentValidationContext;
import com.ps006.upiswitch.validation.model.RuleValidationResult;
import com.ps006.upiswitch.validation.model.ValidationResult;
import com.ps006.upiswitch.validation.validator.PaymentRuleValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ValidationPipelineService {

    private static final Logger log = LoggerFactory.getLogger(ValidationPipelineService.class);

    private final List<PaymentRuleValidator> validators;

    public ValidationPipelineService(List<PaymentRuleValidator> validators) {
        // Enforce deterministic execution order: BR-01 -> BR-02 -> BR-03 -> BR-04 -> BR-05 -> BR-06
        this.validators = validators.stream()
                .sorted(Comparator.comparingInt(PaymentRuleValidator::getOrder))
                .collect(Collectors.toList());
        log.info("Initialized ValidationPipelineService with {} validators in order: {}",
                this.validators.size(),
                this.validators.stream().map(PaymentRuleValidator::getRuleId).collect(Collectors.joining(" -> ")));
    }

    /**
     * Performs structural and identity validation prior to business rules.
     * Structural errors (e.g. unknown VPAs, zero/negative amounts, self-transfer)
     * fail immediately as business rules require valid participant entities.
     */
    public ValidationResult validateIdentityAndStructure(PaymentValidationContext context) {
        if (context.payerVpa() == null || context.payerVpa().trim().isEmpty()) {
            return ValidationResult.failure("INVALID_VPA", "Payer VPA cannot be null or empty");
        }
        if (context.payeeVpa() == null || context.payeeVpa().trim().isEmpty()) {
            return ValidationResult.failure("INVALID_VPA", "Payee VPA cannot be null or empty");
        }
        if (context.payerVpa().equalsIgnoreCase(context.payeeVpa())) {
            return ValidationResult.failure("SELF_TRANSFER_NOT_ALLOWED", "Payer VPA and Payee VPA cannot be identical");
        }
        if (context.amount() == null || context.amount().compareTo(BigDecimal.ZERO) <= 0) {
            return ValidationResult.failure("INVALID_AMOUNT", "Transaction amount must be strictly positive");
        }
        if (context.payerVpaDetails() == null || !context.payerVpaDetails().active()) {
            return ValidationResult.failure("PAYER_NOT_FOUND", "Payer VPA does not exist or is inactive: " + context.payerVpa());
        }
        if (context.payeeVpaDetails() == null || !context.payeeVpaDetails().active()) {
            return ValidationResult.failure("PAYEE_NOT_FOUND", "Payee VPA does not exist or is inactive: " + context.payeeVpa());
        }
        if (context.payerAccount() == null) {
            return ValidationResult.failure("ACCOUNT_NOT_FOUND", "Payer account not found or unlinked");
        }
        return ValidationResult.success();
    }

    /**
     * Executes the ordered business-rule pipeline without short-circuiting.
     * All failed rule IDs are collected in deterministic order.
     * The balance check (BR-06) is guaranteed to execute last.
     */
    public ValidationResult validate(PaymentValidationContext context) {
        List<String> failedRuleIds = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        String primaryErrorCode = null;

        for (PaymentRuleValidator validator : validators) {
            RuleValidationResult result = validator.validate(context);
            if (!result.valid()) {
                failedRuleIds.add(result.ruleId());
                messages.add(result.message());
                if (primaryErrorCode == null && result.errorCode() != null) {
                    primaryErrorCode = result.errorCode();
                }
                log.debug("Txn {} failed rule {}: {}", context.txnId(), result.ruleId(), result.message());
            }
        }

        if (failedRuleIds.isEmpty()) {
            return ValidationResult.success();
        }

        log.info("Txn {} failed validation on rules: {}", context.txnId(), failedRuleIds);
        return ValidationResult.failure(failedRuleIds, primaryErrorCode, messages);
    }

    public List<PaymentRuleValidator> getValidators() {
        return validators;
    }
}
