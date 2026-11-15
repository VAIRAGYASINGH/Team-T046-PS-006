package com.ps006.upiswitch.vpa.entity;

import com.ps006.upiswitch.vpa.model.OwnerType;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "vpas")
public class VpaEntity {

    @Id
    @Column(name = "vpa", length = 128, nullable = false)
    private String vpa;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", length = 16, nullable = false)
    private OwnerType ownerType;

    @Column(name = "owner_id", length = 32, nullable = false)
    private String ownerId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "active", nullable = false)
    private boolean active;

    public VpaEntity() {}

    public VpaEntity(String vpa, Long accountId, OwnerType ownerType, String ownerId, LocalDateTime createdAt, boolean active) {
        this.vpa = vpa;
        this.accountId = accountId;
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
        this.active = active;
    }

    public String getVpa() { return vpa; }
    public void setVpa(String vpa) { this.vpa = vpa; }

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

    public OwnerType getOwnerType() { return ownerType; }
    public void setOwnerType(OwnerType ownerType) { this.ownerType = ownerType; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}

