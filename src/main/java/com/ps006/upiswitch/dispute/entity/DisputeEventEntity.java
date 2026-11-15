package com.ps006.upiswitch.dispute.entity;

import com.ps006.upiswitch.dispute.model.DisputeAction;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "dispute_events")
public class DisputeEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "event_id")
    private Long eventId;

    @Column(name = "dispute_id", length = 64, nullable = false)
    private String disputeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 32, nullable = false)
    private DisputeAction action;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "previous_state", length = 32)
    private String previousState;

    @Column(name = "new_state", length = 32, nullable = false)
    private String newState;

    @Column(name = "deadline")
    private LocalDateTime deadline;

    @Column(name = "rule_ids", length = 64)
    private String ruleIds;

    @Column(name = "remarks", length = 255)
    private String remarks;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Transient
    private String txnId;

    public DisputeEventEntity() {}

    public DisputeEventEntity(String disputeId, DisputeAction action, LocalDateTime timestamp,
                              String previousState, String newState, LocalDateTime deadline,
                              String ruleIds, String remarks) {
        this(disputeId, null, action, timestamp, previousState, newState, deadline, ruleIds, remarks);
    }

    public DisputeEventEntity(String disputeId, String txnId, DisputeAction action, LocalDateTime timestamp,
                              String previousState, String newState, LocalDateTime deadline,
                              String ruleIds, String remarks) {
        this.disputeId = disputeId;
        this.txnId = txnId;
        this.action = action;
        this.timestamp = timestamp;
        this.previousState = previousState;
        this.newState = newState;
        this.deadline = deadline;
        this.ruleIds = ruleIds;
        this.remarks = remarks;
        this.createdAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getEventId() { return eventId; }
    public void setEventId(Long eventId) { this.eventId = eventId; }

    public String getDisputeId() { return disputeId; }
    public void setDisputeId(String disputeId) { this.disputeId = disputeId; }

    public String getTxnId() { return txnId; }
    public void setTxnId(String txnId) { this.txnId = txnId; }

    public DisputeAction getAction() { return action; }
    public void setAction(DisputeAction action) { this.action = action; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public String getPreviousState() { return previousState; }
    public void setPreviousState(String previousState) { this.previousState = previousState; }

    public String getNewState() { return newState; }
    public void setNewState(String newState) { this.newState = newState; }

    public LocalDateTime getDeadline() { return deadline; }
    public void setDeadline(LocalDateTime deadline) { this.deadline = deadline; }

    public String getRuleIds() { return ruleIds; }
    public void setRuleIds(String ruleIds) { this.ruleIds = ruleIds; }

    public String getRemarks() { return remarks; }
    public void setRemarks(String remarks) { this.remarks = remarks; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public String getResult() {
        return "REJECT".equalsIgnoreCase(newState) ? "REJECT" : "SUCCESS";
    }

    public String getRejectionReason() {
        return ruleIds != null && !ruleIds.isEmpty() ? ruleIds : remarks;
    }
}
