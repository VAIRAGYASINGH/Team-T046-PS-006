package com.ps006.upiswitch.common;

import com.ps006.upiswitch.common.util.RrnGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RrnGeneratorTest {

    @BeforeEach
    void setUp() {
        RrnGenerator.resetSequence();
    }

    @Test
    @DisplayName("Verify RRN generation produces strictly 12 digits zero-padded")
    void testRrnGeneration() {
        String rrn = RrnGenerator.generateRrn();
        assertNotNull(rrn);
        assertEquals(12, rrn.length());
        assertEquals("000000000001", rrn);

        String rrn2 = RrnGenerator.generateRrn();
        assertEquals("000000000002", rrn2);
    }
}
