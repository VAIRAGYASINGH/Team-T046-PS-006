package com.ps006.upiswitch.dispute.service;

import com.ps006.upiswitch.common.util.BusinessCalendarService;
import com.ps006.upiswitch.dispute.entity.DisputeEntity;
import com.ps006.upiswitch.dispute.entity.DisputeEventEntity;
import com.ps006.upiswitch.dispute.model.DisputeAction;
import com.ps006.upiswitch.dispute.model.DisputeState;
import com.ps006.upiswitch.dispute.model.DisputeType;
import com.ps006.upiswitch.dispute.repository.DisputeEventRepository;
import com.ps006.upiswitch.dispute.repository.DisputeRepository;
import com.ps006.upiswitch.payment.entity.TransactionEntity;
import com.ps006.upiswitch.payment.model.TransactionStatus;
import com.ps006.upiswitch.payment.repository.TransactionRepository;
import com.ps006.upiswitch.reporting.model.DisputeLogRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Core dispute management and resolution engine adhering to PS-006 specifications,
 * RBI/NPCI guidelines, and statutory SLA workflows (TCC, Chargeback, Pre-arbitration).
 */
@Service
@Transactional
public class DisputeService {

    private static final Logger log = LoggerFactory.getLogger(DisputeService.class);

    private final DisputeRepository disputeRepository;
    private final DisputeEventRepository disputeEventRepository;
    private final TransactionRepository transactionRepository;
    private final BusinessCalendarService businessCalendarService;
    private final DisputeSettlementService disputeSettlementService;

    public DisputeService(DisputeRepository disputeRepository,
                          DisputeEventRepository disputeEventRepository,
                          TransactionRepository transactionRepository,
                          BusinessCalendarService businessCalendarService,
                          DisputeSettlementService disputeSettlementService) {
        this.disputeRepository = disputeRepository;
        this.disputeEventRepository = disputeEventRepository;
        this.transactionRepository = transactionRepository;
        this.businessCalendarService = businessCalendarService;
        this.disputeSettlementService = disputeSettlementService;
    }

    /**
     * Lodges a new dispute (TCC or CHARGEBACK).
     * Enforces statutory filing windows (T+5 for TCC, Day 45 for Chargeback),
     * refundable exposure caps, mutual exclusivity with open TCC, and transaction eligibility.
     */
    public DisputeLogRow raiseDispute(int lineNo, String disputeId, LocalDateTime timestamp,
                                      String txnId, String typeStr, String raisedBy, BigDecimal amount) {
        DisputeType type;
        try {
            type = DisputeType.valueOf(typeStr.trim().toUpperCase());
        } catch (Exception e) {
            log.warn("Invalid dispute type: {}", typeStr);
            return new DisputeLogRow(lineNo, disputeId, txnId, typeStr, "", timestamp, "REJECT", null, "INVALID_DISPUTE_TYPE");
        }

        BigDecimal stdAmount = amount != null ? amount.setScale(2, RoundingMode.UNNECESSARY) : BigDecimal.ZERO;

        // 1. Idempotency & Duplicate Dispute ID check
        if (disputeRepository.findByDisputeId(disputeId).isPresent()) {
            log.warn("Duplicate dispute ID: {}", disputeId);
            return new DisputeLogRow(lineNo, disputeId, txnId, type.name(), "", timestamp, "REJECT", null, "DUPLICATE_DISPUTE_ID");
        }

        // 2. Transaction Existence Check
        TransactionEntity txn = transactionRepository.findByTxnId(txnId).orElse(null);
        if (txn == null) {
            log.warn("Dispute {} raised for unknown txn {}", disputeId, txnId);
            return new DisputeLogRow(lineNo, disputeId, txnId, type.name(), "", timestamp, "REJECT", null, "INVALID_TXN");
        }

        // 3. Transaction Status Eligibility: Cannot dispute reversed or recon shortfall transactions (BR-11)
        if (txn.getStatus() == TransactionStatus.REVERSED || txn.getStatus() == TransactionStatus.RECON_SHORTFALL) {
            log.info("Dispute {} on reversed/shortfall txn {} rejected under BR-11", disputeId, txnId);
            recordDispute(disputeId, txnId, type, raisedBy, stdAmount, timestamp, DisputeState.REJECT, null, "BR-11");
            return new DisputeLogRow(lineNo, disputeId, txnId, type.name(), "", timestamp, "REJECT", null, "BR-11");
        }

        // 4. Transaction Status Eligibility: Only SUCCESS or DEEMED_SUCCESS debits are eligible
        if (txn.getStatus() != TransactionStatus.SUCCESS && txn.getStatus() != TransactionStatus.DEEMED_SUCCESS) {
            log.info("Dispute {} on in-flight or failed txn {} (status: {}) rejected", disputeId, txnId, txn.getStatus());
            recordDispute(disputeId, txnId, type, raisedBy, stdAmount, timestamp, DisputeState.REJECT, null, "INELIGIBLE_TXN_STATUS");
            return new DisputeLogRow(lineNo, disputeId, txnId, type.name(), "", timestamp, "REJECT", null, "INELIGIBLE_TXN_STATUS");
        }

        List<DisputeEntity> existingDisputes = disputeRepository.findByTxnId(txnId);

        // 5. Open TCC Precedence: An open TCC strictly blocks a Chargeback (BR-11)
        if (type == DisputeType.CHARGEBACK) {
            boolean hasOpenTcc = existingDisputes.stream().anyMatch(d ->
                    d.getType() == DisputeType.TCC && d.getStatus() == DisputeState.OPEN_TCC);
            if (hasOpenTcc) {
                log.info("Chargeback {} on txn {} with open TCC rejected under BR-11", disputeId, txnId);
                recordDispute(disputeId, txnId, type, raisedBy, stdAmount, timestamp, DisputeState.REJECT, null, "BR-11");
                return new DisputeLogRow(lineNo, disputeId, txnId, type.name(), "", timestamp, "REJECT", null, "BR-11");
            }
        }

        // 6. TCC Window Validation: Within T+5 calendar days from transaction date (BR-08)
        if (type == DisputeType.TCC) {
            if (!"BENEFICIARY_BANK".equalsIgnoreCase(raisedBy != null ? raisedBy.trim() : "")) {
                log.info("TCC {} raised by {} instead of BENEFICIARY_BANK rejected under BR-08", disputeId, raisedBy);
                recordDispute(disputeId, txnId, type, raisedBy, stdAmount, timestamp, DisputeState.REJECT, null, "BR-08");
                return new DisputeLogRow(lineNo, disputeId, txnId, type.name(), "", timestamp, "REJECT", null, "BR-08");
            }

            long daysSinceTxn = ChronoUnit.DAYS.between(txn.getInitiatedAt().toLocalDate(), timestamp.toLocalDate());
            if (daysSinceTxn > 5) {
                log.info("TCC {} raised {} days after txn {} exceeds T+5 limit under BR-08", disputeId, daysSinceTxn, txnId);
                recordDispute(disputeId, txnId, type, raisedBy, stdAmount, timestamp, DisputeState.REJECT, null, "BR-08");
                return new DisputeLogRow(lineNo, disputeId, txnId, type.name(), "", timestamp, "REJECT", null, "BR-08");
            }
        }

        // 7. Chargeback Window & Refundable Exposure Validation (BR-09)
        if (type == DisputeType.CHARGEBACK) {
            if (!"REMITTER".equalsIgnoreCase(raisedBy != null ? raisedBy.trim() : "")) {
                log.info("Chargeback {} raised by {} instead of REMITTER rejected under BR-09", disputeId, raisedBy);
                recordDispute(disputeId, txnId, type, raisedBy, stdAmount, timestamp, DisputeState.REJECT, null, "BR-09");
                return new DisputeLogRow(lineNo, disputeId, txnId, type.name(), "", timestamp, "REJECT", null, "BR-09");
            }

            long daysSinceTxn = ChronoUnit.DAYS.between(txn.getInitiatedAt().toLocalDate(), timestamp.toLocalDate());
            if (daysSinceTxn > 45) { // Day 45 inclusive, Day 46 rejected
                log.info("Chargeback {} raised {} days after txn {} exceeds 45-day limit under BR-09", disputeId, daysSinceTxn, txnId);
                recordDispute(disputeId, txnId, type, raisedBy, stdAmount, timestamp, DisputeState.REJECT, null, "BR-09");
                return new DisputeLogRow(lineNo, disputeId, txnId, type.name(), "", timestamp, "REJECT", null, "BR-09");
            }

            BigDecimal totalRefunded = txn.getRefundedAmount() != null ? txn.getRefundedAmount() : BigDecimal.ZERO;
            BigDecimal existingActiveChargebacks = existingDisputes.stream()
                    .filter(d -> d.getType() == DisputeType.CHARGEBACK && d.getStatus() != DisputeState.REJECT)
                    .map(DisputeEntity::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal refundableExposure = txn.getAmount().subtract(totalRefunded).subtract(existingActiveChargebacks);
            if (stdAmount.compareTo(refundableExposure) > 0) {
                log.info("Chargeback {} amount {} exceeds refundable exposure {} (txn: {}, refunded: {}, prior cb: {}) under BR-09",
                        disputeId, stdAmount, refundableExposure, txn.getAmount(), totalRefunded, existingActiveChargebacks);
                recordDispute(disputeId, txnId, type, raisedBy, stdAmount, timestamp, DisputeState.REJECT, null, "BR-09");
                return new DisputeLogRow(lineNo, disputeId, txnId, type.name(), "", timestamp, "REJECT", null, "BR-09");
            }
        }

        // 8. Dispute is valid: calculate 5-working-day representment deadline
        LocalDateTime deadline = businessCalendarService.calculateRepresentmentDeadline(timestamp.toLocalDate());
        DisputeState targetState = type == DisputeType.TCC ? DisputeState.OPEN_TCC : DisputeState.OPEN_CHARGEBACK;
        recordDispute(disputeId, txnId, type, raisedBy, stdAmount, timestamp, targetState, deadline, null);

        // FINANCIAL INTEGRATION INVARIANT: Zero money moves on dispute filing
        return new DisputeLogRow(lineNo, disputeId, txnId, type.name(), "", timestamp, targetState.name(), deadline, "");
    }

    /**
     * Processes a dispute action (ACCEPT, REPRESENT, PRE_ARBITRATION, CLOSE).
     * Enforces the strict dispute state machine:
     *   RAISED -> ACCEPTED, REPRESENTED
     *   REPRESENTED -> PRE_ARBITRATION
     *   ACCEPTED -> CLOSED
     *   PRE_ARBITRATION -> CLOSED
     * Reject all illegal transitions.
     */
    public DisputeLogRow handleDisputeAction(int lineNo, String disputeId, LocalDateTime timestamp, String actionStr) {
        DisputeEntity dispute = disputeRepository.findByDisputeIdForUpdate(disputeId).orElse(null);

        if (dispute == null) {
            log.warn("Dispute action for unknown dispute {}", disputeId);
            return new DisputeLogRow(lineNo, disputeId, "UNKNOWN", "UNKNOWN", actionStr, timestamp, "REJECT", null, "INVALID_DISPUTE");
        }

        DisputeAction action;
        try {
            action = DisputeAction.valueOf(actionStr.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown dispute action {}", actionStr);
            return new DisputeLogRow(lineNo, disputeId, dispute.getTxnId(), dispute.getType().name(), actionStr, timestamp, "REJECT", null, "INVALID_ACTION");
        }

        DisputeState previousState = dispute.getStatus();

        // 1. Auto-Acceptance Check: If in OPEN state and 5-working-day deadline has elapsed, auto-accept
        if ((previousState == DisputeState.OPEN_CHARGEBACK || previousState == DisputeState.OPEN_TCC)
                && dispute.getDeadline() != null && timestamp.isAfter(dispute.getDeadline())) {
            log.info("Dispute {} exceeded 5-working-day deadline {}; auto-ACCEPTED", disputeId, dispute.getDeadline());
            dispute.setStatus(DisputeState.AUTO_ACCEPTED);
            dispute.setDeadline(null);
            disputeRepository.save(dispute);
            recordEvent(disputeId, dispute.getTxnId(), DisputeAction.AUTO_ACCEPT, timestamp,
                    previousState.name(), DisputeState.AUTO_ACCEPTED.name(), null, null, "SLA expired: auto-accepted");
            previousState = DisputeState.AUTO_ACCEPTED;

            // Trigger settlement for auto-accepted chargeback
            if (dispute.getType() == DisputeType.CHARGEBACK) {
                disputeSettlementService.settleChargebackAcceptance(dispute, timestamp);
            }
        }

        // 2. Reject actions on terminated or already resolved disputes (BR-10)
        if (previousState == DisputeState.REJECT ||
            previousState == DisputeState.AUTO_ACCEPTED ||
            previousState == DisputeState.CLOSED) {
            log.info("Dispute action {} on terminated dispute {} ({}) rejected under BR-10", action, disputeId, previousState);
            recordEvent(disputeId, dispute.getTxnId(), action, timestamp, previousState.name(), "REJECT", null, "BR-10", "Illegal state transition");
            return new DisputeLogRow(lineNo, disputeId, dispute.getTxnId(), dispute.getType().name(), action.name(), timestamp, "REJECT", null, "BR-10");
        }

        // 3. Evaluate State Machine Transitions
        DisputeState newState;
        LocalDateTime newDeadline = null;

        switch (action) {
            case ACCEPT -> {
                // ACCEPT is valid ONLY from RAISED (OPEN_TCC or OPEN_CHARGEBACK)
                // REPRESENTED -> ACCEPTED is ILLEGAL under Section 5 specification
                if (previousState != DisputeState.OPEN_TCC && previousState != DisputeState.OPEN_CHARGEBACK) {
                    log.info("ACCEPT illegal from state {} for dispute {} under BR-10", previousState, disputeId);
                    recordEvent(disputeId, dispute.getTxnId(), action, timestamp, previousState.name(), "REJECT", null, "BR-10", "Illegal state transition");
                    return new DisputeLogRow(lineNo, disputeId, dispute.getTxnId(), dispute.getType().name(), action.name(), timestamp, "REJECT", null, "BR-10");
                }
                newState = DisputeState.ACCEPTED;
                newDeadline = null;

                // Explicit financial settlement: chargeback acceptance debits payee, credits remitter
                if (dispute.getType() == DisputeType.CHARGEBACK) {
                    disputeSettlementService.settleChargebackAcceptance(dispute, timestamp);
                }
            }

            case REPRESENT -> {
                // REPRESENT is valid ONLY from OPEN_CHARGEBACK
                if (previousState != DisputeState.OPEN_CHARGEBACK) {
                    log.info("REPRESENT illegal from state {} for dispute {} under BR-10", previousState, disputeId);
                    recordEvent(disputeId, dispute.getTxnId(), action, timestamp, previousState.name(), "REJECT", null, "BR-10", "Illegal state transition");
                    return new DisputeLogRow(lineNo, disputeId, dispute.getTxnId(), dispute.getType().name(), action.name(), timestamp, "REJECT", null, "BR-10");
                }
                // Must be within 5-working-day representment deadline
                if (dispute.getDeadline() != null && timestamp.isAfter(dispute.getDeadline())) {
                    log.info("REPRESENT received after deadline {} for dispute {} rejected under BR-10", dispute.getDeadline(), disputeId);
                    recordEvent(disputeId, dispute.getTxnId(), action, timestamp, previousState.name(), "REJECT", null, "BR-10", "Action after SLA deadline");
                    return new DisputeLogRow(lineNo, disputeId, dispute.getTxnId(), dispute.getType().name(), action.name(), timestamp, "REJECT", null, "BR-10");
                }
                newState = DisputeState.REPRESENTED;
                // Pre-arbitration window: exactly 7 calendar days from representment date ending at 23:59:59
                newDeadline = businessCalendarService.calculatePreArbitrationDeadline(timestamp.toLocalDate());
            }

            case PRE_ARBITRATION -> {
                // PRE_ARBITRATION is valid ONLY from REPRESENTED
                if (previousState != DisputeState.REPRESENTED) {
                    log.info("PRE_ARBITRATION illegal from state {} for dispute {} under BR-10", previousState, disputeId);
                    recordEvent(disputeId, dispute.getTxnId(), action, timestamp, previousState.name(), "REJECT", null, "BR-10", "Illegal state transition");
                    return new DisputeLogRow(lineNo, disputeId, dispute.getTxnId(), dispute.getType().name(), action.name(), timestamp, "REJECT", null, "BR-10");
                }
                // Must be within 7-calendar-day deadline (Day 7 inclusive, Day 8 rejected)
                if (dispute.getDeadline() != null && timestamp.isAfter(dispute.getDeadline())) {
                    log.info("PRE_ARBITRATION received after deadline {} for dispute {} rejected under BR-10", dispute.getDeadline(), disputeId);
                    recordEvent(disputeId, dispute.getTxnId(), action, timestamp, previousState.name(), "REJECT", null, "BR-10", "Action after SLA deadline");
                    return new DisputeLogRow(lineNo, disputeId, dispute.getTxnId(), dispute.getType().name(), action.name(), timestamp, "REJECT", null, "BR-10");
                }
                newState = DisputeState.PRE_ARBITRATION;
                newDeadline = null;
            }

            case CLOSE -> {
                // CLOSE is valid from PRE_ARBITRATION or ACCEPTED
                if (previousState != DisputeState.PRE_ARBITRATION && previousState != DisputeState.ACCEPTED) {
                    log.info("CLOSE illegal from state {} for dispute {} under BR-10", previousState, disputeId);
                    recordEvent(disputeId, dispute.getTxnId(), action, timestamp, previousState.name(), "REJECT", null, "BR-10", "Illegal state transition");
                    return new DisputeLogRow(lineNo, disputeId, dispute.getTxnId(), dispute.getType().name(), action.name(), timestamp, "REJECT", null, "BR-10");
                }
                newState = DisputeState.CLOSED;
                newDeadline = null;
            }

            default -> {
                log.warn("Unsupported dispute action {}", action);
                recordEvent(disputeId, dispute.getTxnId(), action, timestamp, previousState.name(), "REJECT", null, "BR-10", "Unsupported action");
                return new DisputeLogRow(lineNo, disputeId, dispute.getTxnId(), dispute.getType().name(), action.name(), timestamp, "REJECT", null, "BR-10");
            }
        }

        // Commit transition
        dispute.setStatus(newState);
        dispute.setDeadline(newDeadline);
        disputeRepository.save(dispute);

        recordEvent(disputeId, dispute.getTxnId(), action, timestamp, previousState.name(), newState.name(), newDeadline, null, "Dispute action " + action);

        return new DisputeLogRow(lineNo, disputeId, dispute.getTxnId(), dispute.getType().name(),
                action.name(), timestamp, newState.name(), newDeadline, "");
    }

    /**
     * Batch job to sweep and auto-accept overdue disputes.
     */
    public int autoAcceptExpiredDisputes(LocalDateTime cutoffTimestamp) {
        List<DisputeEntity> expiredDisputes = disputeRepository.findPendingExpiredDisputes(cutoffTimestamp);
        for (DisputeEntity dispute : expiredDisputes) {
            DisputeState prev = dispute.getStatus();
            dispute.setStatus(DisputeState.AUTO_ACCEPTED);
            dispute.setDeadline(null);
            disputeRepository.save(dispute);
            recordEvent(dispute.getDisputeId(), dispute.getTxnId(), DisputeAction.AUTO_ACCEPT, cutoffTimestamp,
                    prev.name(), DisputeState.AUTO_ACCEPTED.name(), null, null, "Batch sweep auto-accepted");
            if (dispute.getType() == DisputeType.CHARGEBACK) {
                disputeSettlementService.settleChargebackAcceptance(dispute, cutoffTimestamp);
            }
        }
        return expiredDisputes.size();
    }

    private void recordDispute(String disputeId, String txnId, DisputeType type, String raisedBy,
                               BigDecimal amount, LocalDateTime timestamp, DisputeState status,
                               LocalDateTime deadline, String ruleIds) {
        DisputeEntity entity = new DisputeEntity(
                disputeId, txnId, type, raisedBy, amount, timestamp, status, deadline, ruleIds
        );
        disputeRepository.save(entity);

        DisputeEventEntity event = new DisputeEventEntity(
                disputeId, txnId, DisputeAction.LODGE, timestamp, null, status.name(), deadline, ruleIds,
                status == DisputeState.REJECT ? "Dispute rejected: " + ruleIds : "Dispute lodged"
        );
        disputeEventRepository.save(event);
    }

    private void recordEvent(String disputeId, String txnId, DisputeAction action, LocalDateTime timestamp,
                             String previousState, String newState, LocalDateTime deadline,
                             String ruleIds, String remarks) {
        DisputeEventEntity event = new DisputeEventEntity(
                disputeId, txnId, action, timestamp, previousState, newState, deadline, ruleIds, remarks
        );
        disputeEventRepository.save(event);
    }
}
