package com.ps006.upiswitch.validation.validator;

import com.ps006.upiswitch.config.SwitchProperties;
import com.ps006.upiswitch.merchant.model.MerchantProfile;
import com.ps006.upiswitch.payment.model.TransactionType;
import com.ps006.upiswitch.validation.model.PaymentValidationContext;
import com.ps006.upiswitch.validation.model.RuleValidationResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * BR-01: Transaction Cap Validator
 * - P2P payments: ₹1,00,000.00
 * - Verified P2M payments: ₹2,00,000.00
 * - Unverified P2M payments: ₹1,00,000.00
 */
@Component
@Order(10)
public class PerTransactionLimitValidator implements PaymentRuleValidator {

    public static final String RULE_ID = "BR-01";
    private static final BigDecimal P2P_MAX = new BigDecimal("100000.00");
    private static final BigDecimal P2M_VERIFIED_MAX = new BigDecimal("200000.00");
    private static final BigDecimal P2M_UNVERIFIED_MAX = new BigDecimal("100000.00");

    private final SwitchProperties switchProperties;

    public PerTransactionLimitValidator(SwitchProperties switchProperties) {
        this.switchProperties = switchProperties;
    }

    @Override
    public String getRuleId() {
        return RULE_ID;
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    public RuleValidationResult validate(PaymentValidationContext context) {
        BigDecimal limit = resolveLimit(context);
        if (context.amount().compareTo(limit) > 0) {
            return RuleValidationResult.failure(RULE_ID, "AM01",
                    String.format("Transaction amount %s exceeds cap of %s for %s transaction",
                            context.amount(), limit, context.transactionType()));
        }
        return RuleValidationResult.success();
    }

    private BigDecimal resolveLimit(PaymentValidationContext context) {
        if (context.transactionType() == TransactionType.P2M && context.payeeMerchant() != null) {
            MerchantProfile merchant = context.payeeMerchant();
            if (merchant.verified()) {
                return (switchProperties != null && switchProperties.getLimits() != null)
                        ? switchProperties.getLimits().getMerchantVerifiedMax()
                        : P2M_VERIFIED_MAX;
            } else {
                return (switchProperties != null && switchProperties.getLimits() != null)
                        ? switchProperties.getLimits().getMerchantDefaultMax()
                        : P2M_UNVERIFIED_MAX;
            }
        }
        return (switchProperties != null && switchProperties.getLimits() != null)
                ? switchProperties.getLimits().getDefaultMax()
                : P2P_MAX;
    }
}
