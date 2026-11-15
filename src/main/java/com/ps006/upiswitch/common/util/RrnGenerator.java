package com.ps006.upiswitch.common.util;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic Retrieval Reference Number (RRN) generator.
 * Produces continuous 12-digit zero-padded numeric sequences (e.g. 000000000001, 000000000002).
 */
@Component
public class RrnGenerator {

    private static final AtomicLong STATIC_SEQUENCE = new AtomicLong(1);

    public static String generateRrn() {
        long seq = STATIC_SEQUENCE.getAndIncrement();
        return String.format("%012d", seq);
    }

    public static void resetSequence() {
        STATIC_SEQUENCE.set(1);
    }

    public String nextRrn() {
        return generateRrn();
    }

    public void reset() {
        resetSequence();
    }

    public void setSequence(long initialValue) {
        STATIC_SEQUENCE.set(initialValue);
    }
}
