package com.ps006.upiswitch.ledger.entity;

import com.ps006.upiswitch.ledger.model.LegType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ledger_entry_id")
    private Long ledgerEntryId;

    @Column(name = "sequence_number", nullable = false, unique = true)
    private Long sequenceNumber;

    @Column(name = "txn_id", length = 64, nullable = false)
    private String txnId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "vpa", length = 128, nullable = false)
    private String vpa;

    @Enumerated(EnumType.STRING)
    @Column(name = "leg_type", length = 32, nullable = false)
    private LegType legType;

    @Column(name = "debit_amount", precision = 19, scale = 2)
    private BigDecimal debitAmount;

    @Column(name = "credit_amount", precision = 19, scale = 2)
    private BigDecimal creditAmount;

    @Column(name = "balance_after", precision = 19, scale = 2, nullable = false)
    private BigDecimal balanceAfter;

    @Column(name = "entry_timestamp", nullable = false)
    private LocalDateTime entryTimestamp;

    @Column(name = "reference_ledger_entry_id")
    private Long referenceLedgerEntryId;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public LedgerEntryEntity() {}

    public LedgerEntryEntity(Long sequenceNumber, String txnId, Long accountId, String vpa,
                             LegType legType, BigDecimal debitAmount, BigDecimal creditAmount,
                             BigDecimal balanceAfter, LocalDateTime entryTimestamp,
                             Long referenceLedgerEntryId, String description) {
        this.sequenceNumber = sequenceNumber;
        this.txnId = txnId;
        this.accountId = accountId;
        this.vpa = vpa;
        this.legType = legType;
        this.debitAmount = debitAmount;
        this.creditAmount = creditAmount;
        this.balanceAfter = balanceAfter;
        this.entryTimestamp = entryTimestamp;
        this.referenceLedgerEntryId = referenceLedgerEntryId;
        this.description = description;
        this.createdAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        throw new UnsupportedOperationException("Financial ledger entries are strictly immutable and cannot be updated.");
    }

    @PreRemove
    protected void onRemove() {
        throw new UnsupportedOperationException("Financial ledger entries are strictly immutable and cannot be deleted.");
    }

    public Long getLedgerEntryId() { return ledgerEntryId; }
    public void setLedgerEntryId(Long ledgerEntryId) { this.ledgerEntryId = ledgerEntryId; }

    public Long getSequenceNumber() { return sequenceNumber; }
    public void setSequenceNumber(Long sequenceNumber) { this.sequenceNumber = sequenceNumber; }

    public String getTxnId() { return txnId; }
    public void setTxnId(String txnId) { this.txnId = txnId; }

    public Long getAccountId() { return accountId; }
    public void setAccountId(Long accountId) { this.accountId = accountId; }

    public String getVpa() { return vpa; }
    public void setVpa(String vpa) { this.vpa = vpa; }

    public LegType getLegType() { return legType; }
    public void setLegType(LegType legType) { this.legType = legType; }

    public BigDecimal getDebitAmount() { return debitAmount; }
    public void setDebitAmount(BigDecimal debitAmount) { this.debitAmount = debitAmount; }

    public BigDecimal getCreditAmount() { return creditAmount; }
    public void setCreditAmount(BigDecimal creditAmount) { this.creditAmount = creditAmount; }

    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; }

    public LocalDateTime getEntryTimestamp() { return entryTimestamp; }
    public void setEntryTimestamp(LocalDateTime entryTimestamp) { this.entryTimestamp = entryTimestamp; }

    public Long getReferenceLedgerEntryId() { return referenceLedgerEntryId; }
    public void setReferenceLedgerEntryId(Long referenceLedgerEntryId) { this.referenceLedgerEntryId = referenceLedgerEntryId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

