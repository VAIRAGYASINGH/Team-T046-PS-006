package com.ps006.upiswitch.common.exception;

/**
 * Thrown when a business rule or statutory limit is violated.
 */
public class ValidationException extends SwitchException {

    private final String ruleId;

    public ValidationException(String ruleId, String message) {
        super(ruleId, message);
        this.ruleId = ruleId;
    }

    public String getRuleId() {
        return ruleId;
    }
}

