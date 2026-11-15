package com.ps006.upiswitch.common.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SwitchExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SwitchExceptionHandler.class);

    public void handleException(int lineNo, String commandName, Exception ex) {
        if (ex instanceof ValidationException ve) {
            log.warn("Validation failure at line {} for command {}: {}", lineNo, commandName, ve.getMessage());
        } else if (ex instanceof InsufficientFundsException ife) {
            log.warn("Insufficient funds at line {} for command {}: {}", lineNo, commandName, ife.getMessage());
        } else if (ex instanceof EntityNotFoundException enfe) {
            log.warn("Entity not found at line {} for command {}: {}", lineNo, commandName, enfe.getMessage());
        } else if (ex instanceof DuplicateEntityException dee) {
            log.warn("Duplicate entity at line {} for command {}: {}", lineNo, commandName, dee.getMessage());
        } else {
            log.error("Unhandled error at line {} for command {}: {}", lineNo, commandName, ex.getMessage(), ex);
        }
    }
}

