package com.ps006.upiswitch.common.exception;

import java.math.BigDecimal;

/**
 * Thrown when an account does not have sufficient available balance for a debit.
 */
public class InsufficientFundsException extends SwitchException {

    private final BigDecimal availableBalance;
    private final BigDecimal requiredAmount;

    public InsufficientFundsException(String vpa, BigDecimal availableBalance, BigDecimal requiredAmount) {
        super("INSUFFICIENT_FUNDS", String.format("Account for VPA %s has insufficient funds. Available: %s, Required: %s",
                vpa, availableBalance, requiredAmount));
        this.availableBalance = availableBalance;
        this.requiredAmount = requiredAmount;
    }

    public BigDecimal getAvailableBalance() { return availableBalance; }
    public BigDecimal getRequiredAmount() { return requiredAmount; }
}

