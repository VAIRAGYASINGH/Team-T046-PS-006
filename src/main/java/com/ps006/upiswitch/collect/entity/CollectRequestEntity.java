package com.ps006.upiswitch.collect.entity;

import com.ps006.upiswitch.collect.model.CollectStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "collect_requests")
public class CollectRequestEntity {

    @Id
    @Column(name = "txn_id", length = 64, nullable = false)
    private String txnId;

    @Column(name = "requester_vpa", length = 128, nullable = false)
    private String requesterVpa;

    @Column(name = "payer_vpa", length = 128, nullable = false)
    private String payerVpa;

    @Column(name = "amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "expiry_minutes", nullable = false)
    private int expiryMinutes;

    @Column(name = "pin_flag", nullable = false)
    private boolean pinFlag;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private CollectStatus status;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "declined_at")
    private LocalDateTime declinedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public CollectRequestEntity() {}

    public CollectRequestEntity(String txnId, String requesterVpa, String payerVpa, BigDecimal amount,
                                int expiryMinutes, boolean pinFlag, LocalDateTime expiresAt,
                                CollectStatus status) {
        this.txnId = txnId;
        this.requesterVpa = requesterVpa;
        this.payerVpa = payerVpa;
        this.amount = amount;
        this.expiryMinutes = expiryMinutes;
        this.pinFlag = pinFlag;
        this.expiresAt = expiresAt;
        this.status = status != null ? status : CollectStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = CollectStatus.PENDING;
    }

    public String getTxnId() { return txnId; }
    public void setTxnId(String txnId) { this.txnId = txnId; }

    public String getRequestId() { return txnId; }
    public void setRequestId(String requestId) { this.txnId = requestId; }

    public String getRequesterVpa() { return requesterVpa; }
    public void setRequesterVpa(String requesterVpa) { this.requesterVpa = requesterVpa; }

    public String getPayeeVpa() { return requesterVpa; }
    public void setPayeeVpa(String payeeVpa) { this.requesterVpa = payeeVpa; }

    public String getPayerVpa() { return payerVpa; }
    public void setPayerVpa(String payerVpa) { this.payerVpa = payerVpa; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public int getExpiryMinutes() { return expiryMinutes; }
    public void setExpiryMinutes(int expiryMinutes) { this.expiryMinutes = expiryMinutes; }

    public boolean isPinFlag() { return pinFlag; }
    public void setPinFlag(boolean pinFlag) { this.pinFlag = pinFlag; }

    public boolean isPinRequired() { return pinFlag; }
    public void setPinRequired(boolean pinRequired) { this.pinFlag = pinRequired; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getExpiryTimestamp() { return expiresAt; }
    public void setExpiryTimestamp(LocalDateTime expiryTimestamp) { this.expiresAt = expiryTimestamp; }

    public CollectStatus getStatus() { return status; }
    public void setStatus(CollectStatus status) { this.status = status; }

    public LocalDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }

    public LocalDateTime getApprovalTimestamp() { return approvedAt; }
    public void setApprovalTimestamp(LocalDateTime approvalTimestamp) { this.approvedAt = approvalTimestamp; }

    public LocalDateTime getDeclinedAt() { return declinedAt; }
    public void setDeclinedAt(LocalDateTime declinedAt) { this.declinedAt = declinedAt; }

    public LocalDateTime getDeclineTimestamp() { return declinedAt; }
    public void setDeclineTimestamp(LocalDateTime declineTimestamp) { this.declinedAt = declineTimestamp; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getCreationTimestamp() { return createdAt; }
    public void setCreationTimestamp(LocalDateTime creationTimestamp) { this.createdAt = creationTimestamp; }
}

