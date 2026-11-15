package com.ps006.upiswitch.merchant.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "merchants")
public class MerchantEntity {

    @Id
    @Column(name = "merchant_id", length = 32, nullable = false)
    private String merchantId;

    @Column(name = "name", length = 128, nullable = false)
    private String name;

    @Column(name = "mcc", length = 4, nullable = false)
    private String mcc;

    @Column(name = "verified", nullable = false)
    private boolean verified;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public MerchantEntity() {}

    public MerchantEntity(String merchantId, String name, String mcc, boolean verified, LocalDateTime createdAt) {
        this.merchantId = merchantId;
        this.name = name;
        this.mcc = mcc;
        this.verified = verified;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getMcc() { return mcc; }
    public void setMcc(String mcc) { this.mcc = mcc; }

    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

