package com.ps006.upiswitch.common.exception;

/**
 * Thrown when an expected entity (User, Account, VPA, Transaction, Dispute) is not found.
 */
public class EntityNotFoundException extends SwitchException {

    public EntityNotFoundException(String entityName, String identifier) {
        super("NOT_FOUND", entityName + " not found with identifier: " + identifier);
    }
}

