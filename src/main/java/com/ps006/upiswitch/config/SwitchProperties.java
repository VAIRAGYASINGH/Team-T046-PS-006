package com.ps006.upiswitch.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

/**
 * Strongly typed, validated application and regulatory configuration properties.
 */
@Validated
@ConfigurationProperties(prefix = "switch")
public class SwitchProperties {

    private String inputDir = "sample-data";
    private String outputDir = "output";
    private String timezone = "Asia/Kolkata";
    private Limits limits = new Limits();
    private Timeout timeout = new Timeout();

    public static class Limits {
        private BigDecimal p2pTxnCap = new BigDecimal("100000.00");
        private BigDecimal p2mVerifiedTxnCap = new BigDecimal("200000.00");
        private BigDecimal p2mUnverifiedTxnCap = new BigDecimal("100000.00");
        private BigDecimal dailyDebitAmountCap = new BigDecimal("100000.00");
        private int dailyDebitVelocityCount = 20;
        private BigDecimal coolingPeriodAmountCap = new BigDecimal("5000.00");
        private int coolingPeriodHours = 24;
        private int coolingNewBeneficiaryMaxTxns = 3;
        private BigDecimal collectPinThreshold = new BigDecimal("2000.00");
        private int collectMaxExpiryMinutes = 4320;
        private int timeoutMinutes = 30;
        private int tccWindowCalendarDays = 5;
        private int chargebackWindowCalendarDays = 45;
        private int disputeRepresentmentWorkingDays = 5;
        private int disputePreArbitrationCalendarDays = 7;

        public BigDecimal getP2pTxnCap() { return p2pTxnCap; }
        public void setP2pTxnCap(BigDecimal p2pTxnCap) { this.p2pTxnCap = p2pTxnCap; }

        public BigDecimal getDefaultMax() { return p2pTxnCap; }

        public BigDecimal getP2mVerifiedTxnCap() { return p2mVerifiedTxnCap; }
        public void setP2mVerifiedTxnCap(BigDecimal p2mVerifiedTxnCap) { this.p2mVerifiedTxnCap = p2mVerifiedTxnCap; }

        public BigDecimal getMerchantVerifiedMax() { return p2mVerifiedTxnCap; }

        public BigDecimal getP2mUnverifiedTxnCap() { return p2mUnverifiedTxnCap; }
        public void setP2mUnverifiedTxnCap(BigDecimal p2mUnverifiedTxnCap) { this.p2mUnverifiedTxnCap = p2mUnverifiedTxnCap; }

        public BigDecimal getMerchantDefaultMax() { return p2mUnverifiedTxnCap; }

        public BigDecimal getDailyDebitAmountCap() { return dailyDebitAmountCap; }
        public void setDailyDebitAmountCap(BigDecimal dailyDebitAmountCap) { this.dailyDebitAmountCap = dailyDebitAmountCap; }

        public BigDecimal getDailyMax() { return dailyDebitAmountCap; }

        public int getDailyDebitVelocityCount() { return dailyDebitVelocityCount; }
        public void setDailyDebitVelocityCount(int dailyDebitVelocityCount) { this.dailyDebitVelocityCount = dailyDebitVelocityCount; }

        public int getDailyMaxDebitCount() { return dailyDebitVelocityCount; }

        public BigDecimal getCoolingPeriodAmountCap() { return coolingPeriodAmountCap; }
        public void setCoolingPeriodAmountCap(BigDecimal coolingPeriodAmountCap) { this.coolingPeriodAmountCap = coolingPeriodAmountCap; }

        public BigDecimal getNewBeneficiaryLimit() { return coolingPeriodAmountCap; }

        public int getCoolingPeriodHours() { return coolingPeriodHours; }
        public void setCoolingPeriodHours(int coolingPeriodHours) { this.coolingPeriodHours = coolingPeriodHours; }

        public int getCoolingNewBeneficiaryMaxTxns() { return coolingNewBeneficiaryMaxTxns; }
        public void setCoolingNewBeneficiaryMaxTxns(int coolingNewBeneficiaryMaxTxns) { this.coolingNewBeneficiaryMaxTxns = coolingNewBeneficiaryMaxTxns; }

        public BigDecimal getCollectPinThreshold() { return collectPinThreshold; }
        public void setCollectPinThreshold(BigDecimal collectPinThreshold) { this.collectPinThreshold = collectPinThreshold; }

        public int getCollectMaxExpiryMinutes() { return collectMaxExpiryMinutes; }
        public void setCollectMaxExpiryMinutes(int collectMaxExpiryMinutes) { this.collectMaxExpiryMinutes = collectMaxExpiryMinutes; }

        public int getTimeoutMinutes() { return timeoutMinutes; }
        public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }

        public int getTccWindowCalendarDays() { return tccWindowCalendarDays; }
        public void setTccWindowCalendarDays(int tccWindowCalendarDays) { this.tccWindowCalendarDays = tccWindowCalendarDays; }

        public int getChargebackWindowCalendarDays() { return chargebackWindowCalendarDays; }
        public void setChargebackWindowCalendarDays(int chargebackWindowCalendarDays) { this.chargebackWindowCalendarDays = chargebackWindowCalendarDays; }

        public int getDisputeRepresentmentWorkingDays() { return disputeRepresentmentWorkingDays; }
        public void setDisputeRepresentmentWorkingDays(int disputeRepresentmentWorkingDays) { this.disputeRepresentmentWorkingDays = disputeRepresentmentWorkingDays; }

        public int getDisputePreArbitrationCalendarDays() { return disputePreArbitrationCalendarDays; }
        public void setDisputePreArbitrationCalendarDays(int disputePreArbitrationCalendarDays) { this.disputePreArbitrationCalendarDays = disputePreArbitrationCalendarDays; }
    }

    public static class Timeout {
        private long pendingPaymentTimeoutMinutes = 30L;

        public long getPendingPaymentTimeoutMinutes() { return pendingPaymentTimeoutMinutes; }
        public void setPendingPaymentTimeoutMinutes(long pendingPaymentTimeoutMinutes) { this.pendingPaymentTimeoutMinutes = pendingPaymentTimeoutMinutes; }
    }

    public String getInputDir() { return inputDir; }
    public void setInputDir(String inputDir) { this.inputDir = inputDir; }

    public String getOutputDir() { return outputDir; }
    public void setOutputDir(String outputDir) { this.outputDir = outputDir; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public Limits getLimits() { return limits; }
    public void setLimits(Limits limits) { this.limits = limits; }

    public Timeout getTimeout() { return timeout; }
    public void setTimeout(Timeout timeout) { this.timeout = timeout; }
}
