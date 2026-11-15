package com.ps006.upiswitch.dispute.entity;

import com.ps006.upiswitch.dispute.model.DisputeState;
import com.ps006.upiswitch.dispute.model.DisputeType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "disputes")
public class DisputeEntity {

    @Id
    @Column(name = "dispute_id", length = 64, nullable = false)
    private String disputeId;

    @Column(name = "txn_id", length = 64, nullable = false)
    private String txnId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 32, nullable = false)
    private DisputeType type;

    @Column(name = "raised_by", length = 32, nullable = false)
    private String raisedBy;

    @Column(name = "amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "lodged_at", nullable = false)
    private LocalDateTime lodgedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private DisputeState status;

    @Column(name = "deadline")
    private LocalDateTime deadline;

    @Column(name = "rule_ids", length = 64)
    private String ruleIds;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public DisputeEntity() {}

    public DisputeEntity(String disputeId, String txnId, DisputeType type, String raisedBy,
                         BigDecimal amount, LocalDateTime lodgedAt, DisputeState status,
                         LocalDateTime deadline, String ruleIds) {
        this.disputeId = disputeId;
        this.txnId = txnId;
        this.type = type;
        this.raisedBy = raisedBy;
        this.amount = amount;
        this.lodgedAt = lodgedAt;
        this.status = status;
        this.deadline = deadline;
        this.ruleIds = ruleIds;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public String getDisputeId() { return disputeId; }
    public void setDisputeId(String disputeId) { this.disputeId = disputeId; }

    public String getTxnId() { return txnId; }
    public void setTxnId(String txnId) { this.txnId = txnId; }

    public DisputeType getType() { return type; }
    public void setType(DisputeType type) { this.type = type; }

    public String getRaisedBy() { return raisedBy; }
    public void setRaisedBy(String raisedBy) { this.raisedBy = raisedBy; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public LocalDateTime getLodgedAt() { return lodgedAt; }
    public void setLodgedAt(LocalDateTime lodgedAt) { this.lodgedAt = lodgedAt; }

    public DisputeState getStatus() { return status; }
    public void setStatus(DisputeState status) { this.status = status; }

    public LocalDateTime getDeadline() { return deadline; }
    public void setDeadline(LocalDateTime deadline) { this.deadline = deadline; }

    public String getRuleIds() { return ruleIds; }
    public void setRuleIds(String ruleIds) { this.ruleIds = ruleIds; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

