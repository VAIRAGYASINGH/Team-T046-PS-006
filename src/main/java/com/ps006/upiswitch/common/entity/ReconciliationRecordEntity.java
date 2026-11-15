package com.ps006.upiswitch.common.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "reconciliation_records")
public class ReconciliationRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "recon_id")
    private Long reconId;

    @Column(name = "txn_id", length = 64, nullable = false)
    private String txnId;

    @Column(name = "incident_type", length = 32, nullable = false)
    private String incidentType;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @Column(name = "amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "payer_vpa", length = 128, nullable = false)
    private String payerVpa;

    @Column(name = "payee_vpa", length = 128, nullable = false)
    private String payeeVpa;

    @Column(name = "payer_available_balance", precision = 19, scale = 2, nullable = false)
    private BigDecimal payerAvailableBalance;

    @Column(name = "resolution_status", length = 32, nullable = false)
    private String resolutionStatus;

    @Column(name = "details")
    private String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public ReconciliationRecordEntity() {}

    public ReconciliationRecordEntity(String txnId, String incidentType, LocalDateTime recordedAt,
                                      BigDecimal amount, String payerVpa, String payeeVpa,
                                      BigDecimal payerAvailableBalance, String resolutionStatus, String details) {
        this.txnId = txnId;
        this.incidentType = incidentType;
        this.recordedAt = recordedAt;
        this.amount = amount;
        this.payerVpa = payerVpa;
        this.payeeVpa = payeeVpa;
        this.payerAvailableBalance = payerAvailableBalance;
        this.resolutionStatus = resolutionStatus != null ? resolutionStatus : "PENDING";
        this.details = details;
        this.createdAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (resolutionStatus == null) resolutionStatus = "PENDING";
    }

    public Long getReconId() { return reconId; }
    public void setReconId(Long reconId) { this.reconId = reconId; }

    public String getTxnId() { return txnId; }
    public void setTxnId(String txnId) { this.txnId = txnId; }

    public String getIncidentType() { return incidentType; }
    public String getRecordType() { return incidentType; }
    public void setIncidentType(String incidentType) { this.incidentType = incidentType; }

    public LocalDateTime getRecordedAt() { return recordedAt; }
    public void setRecordedAt(LocalDateTime recordedAt) { this.recordedAt = recordedAt; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getPayerVpa() { return payerVpa; }
    public void setPayerVpa(String payerVpa) { this.payerVpa = payerVpa; }

    public String getPayeeVpa() { return payeeVpa; }
    public void setPayeeVpa(String payeeVpa) { this.payeeVpa = payeeVpa; }

    public BigDecimal getPayerAvailableBalance() { return payerAvailableBalance; }
    public void setPayerAvailableBalance(BigDecimal payerAvailableBalance) { this.payerAvailableBalance = payerAvailableBalance; }

    public String getResolutionStatus() { return resolutionStatus; }
    public void setResolutionStatus(String resolutionStatus) { this.resolutionStatus = resolutionStatus; }

    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

