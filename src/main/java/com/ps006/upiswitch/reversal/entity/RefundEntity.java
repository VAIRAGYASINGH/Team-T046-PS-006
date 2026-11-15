package com.ps006.upiswitch.reversal.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "refunds")
public class RefundEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "refund_id")
    private Long refundId;

    @Column(name = "original_txn_id", length = 64, nullable = false)
    private String originalTxnId;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "merchant_vpa", length = 128, nullable = false)
    private String merchantVpa;

    @Column(name = "customer_vpa", length = 128, nullable = false)
    private String customerVpa;

    @Column(name = "status", length = 32, nullable = false)
    private String status;

    @Column(name = "rrn", length = 12, nullable = false)
    private String rrn;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public RefundEntity() {}

    public RefundEntity(String originalTxnId, LocalDateTime timestamp, BigDecimal amount,
                        String merchantVpa, String customerVpa, String status, String rrn) {
        this.originalTxnId = originalTxnId;
        this.timestamp = timestamp;
        this.amount = amount;
        this.merchantVpa = merchantVpa;
        this.customerVpa = customerVpa;
        this.status = status;
        this.rrn = rrn;
        this.createdAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getRefundId() { return refundId; }
    public void setRefundId(Long refundId) { this.refundId = refundId; }

    public String getOriginalTxnId() { return originalTxnId; }
    public void setOriginalTxnId(String originalTxnId) { this.originalTxnId = originalTxnId; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getMerchantVpa() { return merchantVpa; }
    public void setMerchantVpa(String merchantVpa) { this.merchantVpa = merchantVpa; }

    public String getCustomerVpa() { return customerVpa; }
    public void setCustomerVpa(String customerVpa) { this.customerVpa = customerVpa; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getRrn() { return rrn; }
    public void setRrn(String rrn) { this.rrn = rrn; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

