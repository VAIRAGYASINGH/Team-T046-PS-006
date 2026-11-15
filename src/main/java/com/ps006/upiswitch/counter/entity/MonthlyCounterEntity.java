package com.ps006.upiswitch.counter.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "monthly_counters", uniqueConstraints = {
        @UniqueConstraint(name = "uq_user_monthly_counter", columnNames = {"user_id", "year_month"})
})
public class MonthlyCounterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "monthly_counter_id")
    private Long monthlyCounterId;

    @Column(name = "user_id", length = 32, nullable = false)
    private String userId;

    @Column(name = "year_month", length = 7, nullable = false)
    private String yearMonth;

    @Column(name = "debit_count", nullable = false)
    private int debitCount;

    @Column(name = "debit_amount", precision = 19, scale = 2, nullable = false)
    private BigDecimal debitAmount;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public MonthlyCounterEntity() {}

    public MonthlyCounterEntity(String userId, String yearMonth, int debitCount, BigDecimal debitAmount) {
        this.userId = userId;
        this.yearMonth = yearMonth;
        this.debitCount = debitCount;
        this.debitAmount = debitAmount != null ? debitAmount : BigDecimal.ZERO.setScale(2);
        this.version = 0L;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
        if (debitAmount == null) debitAmount = BigDecimal.ZERO.setScale(2);
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getMonthlyCounterId() { return monthlyCounterId; }
    public void setMonthlyCounterId(Long monthlyCounterId) { this.monthlyCounterId = monthlyCounterId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getYearMonth() { return yearMonth; }
    public void setYearMonth(String yearMonth) { this.yearMonth = yearMonth; }

    public int getDebitCount() { return debitCount; }
    public void setDebitCount(int debitCount) { this.debitCount = debitCount; }

    public BigDecimal getDebitAmount() { return debitAmount; }
    public void setDebitAmount(BigDecimal debitAmount) { this.debitAmount = debitAmount; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}

