package com.ps006.upiswitch.common.exception;

/**
 * Root unchecked exception for all UPI Payment Switch operations.
 */
public class SwitchException extends RuntimeException {

    private final String errorCode;

    public SwitchException(String message) {
        super(message);
        this.errorCode = "SWITCH_ERROR";
    }

    public SwitchException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public SwitchException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

