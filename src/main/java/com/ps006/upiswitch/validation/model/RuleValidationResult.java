package com.ps006.upiswitch.validation.model;

public record RuleValidationResult(
        boolean valid,
        String ruleId,
        String errorCode,
        String message
) {
    public static RuleValidationResult success() {
        return new RuleValidationResult(true, null, null, null);
    }

    public static RuleValidationResult failure(String ruleId, String errorCode, String message) {
        return new RuleValidationResult(false, ruleId, errorCode, message);
    }

    public static RuleValidationResult failure(String ruleId, String message) {
        return new RuleValidationResult(false, ruleId, null, message);
    }
}

