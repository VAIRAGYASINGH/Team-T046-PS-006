package com.ps006.upiswitch.payment.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "beneficiary_payment_history", uniqueConstraints = {
        @UniqueConstraint(name = "uq_payer_payee_history", columnNames = {"payer_vpa", "payee_vpa"})
})
public class BeneficiaryPaymentHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long historyId;

    @Column(name = "payer_vpa", length = 128, nullable = false)
    private String payerVpa;

    @Column(name = "payee_vpa", length = 128, nullable = false)
    private String payeeVpa;

    @Column(name = "first_payment_at", nullable = false)
    private LocalDateTime firstPaymentAt;

    @Column(name = "payment_count", nullable = false)
    private int paymentCount;

    @Column(name = "last_payment_at", nullable = false)
    private LocalDateTime lastPaymentAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public BeneficiaryPaymentHistoryEntity() {}

    public BeneficiaryPaymentHistoryEntity(String payerVpa, String payeeVpa, LocalDateTime firstPaymentAt,
                                           int paymentCount, LocalDateTime lastPaymentAt) {
        this.payerVpa = payerVpa;
        this.payeeVpa = payeeVpa;
        this.firstPaymentAt = firstPaymentAt;
        this.paymentCount = paymentCount;
        this.lastPaymentAt = lastPaymentAt;
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

    public Long getHistoryId() { return historyId; }
    public void setHistoryId(Long historyId) { this.historyId = historyId; }

    public String getPayerVpa() { return payerVpa; }
    public void setPayerVpa(String payerVpa) { this.payerVpa = payerVpa; }

    public String getPayeeVpa() { return payeeVpa; }
    public void setPayeeVpa(String payeeVpa) { this.payeeVpa = payeeVpa; }

    public LocalDateTime getFirstPaymentAt() { return firstPaymentAt; }
    public void setFirstPaymentAt(LocalDateTime firstPaymentAt) { this.firstPaymentAt = firstPaymentAt; }

    public int getPaymentCount() { return paymentCount; }
    public void setPaymentCount(int paymentCount) { this.paymentCount = paymentCount; }

    public LocalDateTime getLastPaymentAt() { return lastPaymentAt; }
    public void setLastPaymentAt(LocalDateTime lastPaymentAt) { this.lastPaymentAt = lastPaymentAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

