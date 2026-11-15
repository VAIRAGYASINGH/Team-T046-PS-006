package com.ps006.upiswitch.validation.model;

import java.util.Collections;
import java.util.List;

public record ValidationResult(
        boolean valid,
        List<String> failedRuleIds,
        String errorCode,
        List<String> messages
) {
    public static ValidationResult success() {
        return new ValidationResult(true, Collections.emptyList(), null, Collections.emptyList());
    }

    public static ValidationResult failure(List<String> failedRuleIds, String errorCode, List<String> messages) {
        return new ValidationResult(false, failedRuleIds, errorCode, messages);
    }

    public static ValidationResult failure(String ruleId, String message) {
        return new ValidationResult(false, List.of(ruleId), null, List.of(message));
    }

    public static ValidationResult failure(String ruleId, String errorCode, String message) {
        return new ValidationResult(false, List.of(ruleId), errorCode, List.of(message));
    }

    public String ruleId() {
        return (failedRuleIds != null && !failedRuleIds.isEmpty()) ? failedRuleIds.get(0) : null;
    }

    public String ruleIdsString() {
        return (failedRuleIds != null && !failedRuleIds.isEmpty()) ? String.join(";", failedRuleIds) : "";
    }

    public String message() {
        return (messages != null && !messages.isEmpty()) ? messages.get(0) : null;
    }

    public boolean hasRule(String targetRuleId) {
        return failedRuleIds != null && failedRuleIds.contains(targetRuleId);
    }
}
