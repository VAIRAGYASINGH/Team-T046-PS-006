package com.ps006.upiswitch.validation.validator;

import com.ps006.upiswitch.config.SwitchProperties;
import com.ps006.upiswitch.validation.model.PaymentValidationContext;
import com.ps006.upiswitch.validation.model.RuleValidationResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * BR-02: New VPA Cooling Validator
 * - For 24 hours from VPA creation timestamp, transactions are capped at ₹5,000.00.
 * - At exactly 24 hours (86,400 seconds), cooling is expired.
 */
@Component
@Order(20)
public class NewVpaCoolingValidator implements PaymentRuleValidator {

    public static final String RULE_ID = "BR-02";
    private static final BigDecimal COOLING_LIMIT = new BigDecimal("5000.00");
    private static final long TWENTY_FOUR_HOURS_SECONDS = 24 * 3600L;

    private final SwitchProperties switchProperties;

    public NewVpaCoolingValidator(SwitchProperties switchProperties) {
        this.switchProperties = switchProperties;
    }

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public int getOrder() {
        return 20;
    }

    @Override
    public RuleValidationResult validate(PaymentValidationContext context) {
        LocalDateTime vpaCreatedAt = null;

        if (context.payerVpaDetails() != null && context.payerVpaDetails().createdAt() != null) {
            vpaCreatedAt = context.payerVpaDetails().createdAt();
        }

        if (vpaCreatedAt != null) {
            Duration duration = Duration.between(vpaCreatedAt, context.timestamp());
            // Cooling applies strictly while duration is < 24 hours (i.e. < 86,400 seconds)
            if (!duration.isNegative() && duration.toSeconds() < TWENTY_FOUR_HOURS_SECONDS) {
                BigDecimal maxAllowed = (switchProperties != null && switchProperties.getLimits() != null)
                        ? switchProperties.getLimits().getNewBeneficiaryLimit()
                        : COOLING_LIMIT;

                if (context.amount().compareTo(maxAllowed) > 0) {
                    long remainingSeconds = TWENTY_FOUR_HOURS_SECONDS - duration.toSeconds();
                    long remainingMinutes = (remainingSeconds + 59) / 60;
                    return RuleValidationResult.failure(RULE_ID, "U29",
                            String.format("Transaction amount %s exceeds new VPA cooling limit of %s within 24 hours (%d minutes remaining)",
                                    context.amount(), maxAllowed, remainingMinutes));
                }
            }
        }

        return RuleValidationResult.success();
    }
}

