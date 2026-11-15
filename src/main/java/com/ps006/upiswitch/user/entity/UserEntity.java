package com.ps006.upiswitch.user.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @Column(name = "user_id", length = 32, nullable = false)
    private String userId;

    @Column(name = "name", length = 128, nullable = false)
    private String name;

    @Column(name = "mobile", length = 20, nullable = false)
    private String mobile;

    @Column(name = "device_bound_at")
    private LocalDateTime deviceBoundAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public UserEntity() {}

    public UserEntity(String userId, String name, String mobile, LocalDateTime deviceBoundAt, LocalDateTime createdAt) {
        this.userId = userId;
        this.name = name;
        this.mobile = mobile;
        this.deviceBoundAt = deviceBoundAt;
        this.createdAt = createdAt != null ? createdAt : LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getMobile() { return mobile; }
    public void setMobile(String mobile) { this.mobile = mobile; }

    public LocalDateTime getDeviceBoundAt() { return deviceBoundAt; }
    public void setDeviceBoundAt(LocalDateTime deviceBoundAt) { this.deviceBoundAt = deviceBoundAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

