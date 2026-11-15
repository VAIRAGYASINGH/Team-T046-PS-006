package com.ps006.upiswitch.common.exception;

/**
 * Thrown when an idempotent submission detects a duplicate entity (e.g. duplicate txn_id).
 */
public class DuplicateEntityException extends SwitchException {

    public DuplicateEntityException(String entityName, String identifier) {
        super("DUPLICATE_ENTITY", "Duplicate " + entityName + " detected: " + identifier);
    }
}

