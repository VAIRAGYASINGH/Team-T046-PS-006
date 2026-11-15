package com.ps006.upiswitch.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SwitchPropertiesTest {

    @Test
    @DisplayName("Verify default configuration values match NPCI regulations")
    void testRegulatoryDefaults() {
        SwitchProperties properties = new SwitchProperties();

        assertNotNull(properties.getLimits());
        assertEquals(new BigDecimal("100000.00"), properties.getLimits().getP2pTxnCap());
        assertEquals(new BigDecimal("200000.00"), properties.getLimits().getP2mVerifiedTxnCap());
        assertEquals(new BigDecimal("100000.00"), properties.getLimits().getP2mUnverifiedTxnCap());
        assertEquals(new BigDecimal("100000.00"), properties.getLimits().getDailyDebitAmountCap());
        assertEquals(20, properties.getLimits().getDailyDebitVelocityCount());
        assertEquals(new BigDecimal("5000.00"), properties.getLimits().getCoolingPeriodAmountCap());
        assertEquals(24, properties.getLimits().getCoolingPeriodHours());
        assertEquals(3, properties.getLimits().getCoolingNewBeneficiaryMaxTxns());
        assertEquals(new BigDecimal("2000.00"), properties.getLimits().getCollectPinThreshold());
        assertEquals(4320, properties.getLimits().getCollectMaxExpiryMinutes());
        assertEquals(30, properties.getLimits().getTimeoutMinutes());
        assertEquals(5, properties.getLimits().getTccWindowCalendarDays());
        assertEquals(45, properties.getLimits().getChargebackWindowCalendarDays());
        assertEquals(5, properties.getLimits().getDisputeRepresentmentWorkingDays());
        assertEquals("Asia/Kolkata", properties.getTimezone());
        assertEquals("sample-data", properties.getInputDir());
        assertEquals("output", properties.getOutputDir());
    }
}
