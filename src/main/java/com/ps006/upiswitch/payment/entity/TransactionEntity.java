package com.ps006.upiswitch.payment.entity;

import com.ps006.upiswitch.payment.model.TransactionStatus;
import com.ps006.upiswitch.payment.model.TransactionType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "transactions")
public class TransactionEntity {

    @Id
    @Column(name = "txn_id", length = 64, nullable = false)
    private String txnId;

    @Column(name = "rrn", length = 12, nullable = false, unique = true)
    private String rrn;

    @Column(name = "command", length = 32, nullable = false)
    private String command;

    @Column(name = "payer_vpa", length = 128, nullable = false)
    private String payerVpa;

    @Column(name = "payee_vpa", length = 128, nullable = false)
    private String payeeVpa;

    @Column(name = "amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "remarks", length = 255)
    private String remarks;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", length = 16, nullable = false)
    private TransactionType transactionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private TransactionStatus status;

    @Column(name = "rule_ids", length = 64)
    private String ruleIds;

    @Column(name = "refunded_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal refundedAmount;

    @Column(name = "initiated_at", nullable = false)
    private LocalDateTime initiatedAt;

    @Column(name = "reversed_at")
    private LocalDateTime reversedAt;

    @Column(name = "deemed_success_at")
    private LocalDateTime deemedSuccessAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public TransactionEntity() {}

    public TransactionEntity(String txnId, String rrn, String command, String payerVpa, String payeeVpa,
                             BigDecimal amount, String remarks, TransactionType transactionType,
                             TransactionStatus status, String ruleIds, BigDecimal refundedAmount,
                             LocalDateTime initiatedAt, LocalDateTime reversedAt, LocalDateTime deemedSuccessAt) {
        this.txnId = txnId;
        this.rrn = rrn;
        this.command = command;
        this.payerVpa = payerVpa;
        this.payeeVpa = payeeVpa;
        this.amount = amount;
        this.remarks = remarks;
        this.transactionType = transactionType;
        this.status = status;
        this.ruleIds = ruleIds;
        this.refundedAmount = refundedAmount != null ? refundedAmount : BigDecimal.ZERO.setScale(2);
        this.initiatedAt = initiatedAt;
        this.reversedAt = reversedAt;
        this.deemedSuccessAt = deemedSuccessAt;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
        if (refundedAmount == null) refundedAmount = BigDecimal.ZERO.setScale(2);
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public String getTxnId() { return txnId; }
    public void setTxnId(String txnId) { this.txnId = txnId; }

    public String getRrn() { return rrn; }
    public void setRrn(String rrn) { this.rrn = rrn; }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public String getPayerVpa() { return payerVpa; }
    public void setPayerVpa(String payerVpa) { this.payerVpa = payerVpa; }

    public String getPayeeVpa() { return payeeVpa; }
    public void setPayeeVpa(String payeeVpa) { this.payeeVpa = payeeVpa; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getRemarks() { return remarks; }
    public void setRemarks(String remarks) { this.remarks = remarks; }

    public TransactionType getTransactionType() { return transactionType; }
    public void setTransactionType(TransactionType transactionType) { this.transactionType = transactionType; }

    public TransactionStatus getStatus() { return status; }
    public void setStatus(TransactionStatus status) { this.status = status; }

    public String getRuleIds() { return ruleIds; }
    public void setRuleIds(String ruleIds) { this.ruleIds = ruleIds; }

    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public void setRefundedAmount(BigDecimal refundedAmount) { this.refundedAmount = refundedAmount; }

    public LocalDateTime getInitiatedAt() { return initiatedAt; }
    public void setInitiatedAt(LocalDateTime initiatedAt) { this.initiatedAt = initiatedAt; }

    public LocalDateTime getReversedAt() { return reversedAt; }
    public void setReversedAt(LocalDateTime reversedAt) { this.reversedAt = reversedAt; }

    public LocalDateTime getDeemedSuccessAt() { return deemedSuccessAt; }
    public void setDeemedSuccessAt(LocalDateTime deemedSuccessAt) { this.deemedSuccessAt = deemedSuccessAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

