package com.ps006.upiswitch.dispute;

import com.ps006.upiswitch.account.entity.AccountEntity;
import com.ps006.upiswitch.account.repository.AccountRepository;
import com.ps006.upiswitch.common.util.BusinessCalendarService;
import com.ps006.upiswitch.dispute.entity.DisputeEntity;
import com.ps006.upiswitch.dispute.entity.DisputeEventEntity;
import com.ps006.upiswitch.dispute.model.DisputeAction;
import com.ps006.upiswitch.dispute.model.DisputeState;
import com.ps006.upiswitch.dispute.model.DisputeType;
import com.ps006.upiswitch.dispute.repository.DisputeEventRepository;
import com.ps006.upiswitch.dispute.repository.DisputeRepository;
import com.ps006.upiswitch.dispute.service.DisputeService;
import com.ps006.upiswitch.dispute.service.DisputeSettlementService;
import com.ps006.upiswitch.ledger.model.LegType;
import com.ps006.upiswitch.ledger.service.LedgerService;
import com.ps006.upiswitch.payment.entity.TransactionEntity;
import com.ps006.upiswitch.payment.model.TransactionStatus;
import com.ps006.upiswitch.payment.repository.TransactionRepository;
import com.ps006.upiswitch.reporting.model.DisputeLogRow;
import com.ps006.upiswitch.vpa.entity.VpaEntity;
import com.ps006.upiswitch.vpa.repository.VpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@DisplayName("PS-006 Phase 8: Dispute Management Engine Comprehensive Quality Tests")
public class DisputeEngineComprehensiveTest {

    private DisputeRepository disputeRepository;
    private DisputeEventRepository disputeEventRepository;
    private TransactionRepository transactionRepository;
    private AccountRepository accountRepository;
    private LedgerService ledgerService;
    private VpaRepository vpaRepository;

    private BusinessCalendarService businessCalendarService;
    private DisputeSettlementService disputeSettlementService;
    private DisputeService disputeService;

    private final Clock clock = Clock.system(ZoneId.of("Asia/Kolkata"));

    @BeforeEach
    void setUp() throws IOException {
        disputeRepository = mock(DisputeRepository.class);
        disputeEventRepository = mock(DisputeEventRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        ledgerService = mock(LedgerService.class);
        vpaRepository = mock(VpaRepository.class);

        businessCalendarService = new BusinessCalendarService(clock);

        // Load actual holidays from holidays.csv
        Path holidaysPath = Paths.get("sample-data/T046-PS-006-sample-data/holidays.csv");
        if (Files.exists(holidaysPath)) {
            try (BufferedReader reader = Files.newBufferedReader(holidaysPath)) {
                reader.readLine(); // skip header
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split(",", -1);
                    if (parts.length >= 1 && !parts[0].trim().isEmpty()) {
                        businessCalendarService.addHoliday(LocalDate.parse(parts[0].trim()));
                    }
                }
            }
        } else {
            // Fallback to statutory holidays defined in PS-006 specification
            businessCalendarService.addHoliday(LocalDate.of(2026, 10, 2));  // Gandhi Jayanti
            businessCalendarService.addHoliday(LocalDate.of(2026, 10, 20)); // Vijaya Dashami
            businessCalendarService.addHoliday(LocalDate.of(2026, 11, 9));  // Deepavali
            businessCalendarService.addHoliday(LocalDate.of(2026, 11, 24)); // Guru Nanak Jayanti
        }

        disputeSettlementService = new DisputeSettlementService(
                accountRepository, ledgerService, transactionRepository, vpaRepository
        );

        disputeService = new DisputeService(
                disputeRepository, disputeEventRepository, transactionRepository,
                businessCalendarService, disputeSettlementService
        );
    }

    private TransactionEntity createMockTxn(String txnId, String payerVpa, String payeeVpa,
                                            BigDecimal amount, BigDecimal refunded,
                                            LocalDateTime initiatedAt, TransactionStatus status) {
        TransactionEntity txn = new TransactionEntity();
        txn.setTxnId(txnId);
        txn.setPayerVpa(payerVpa);
        txn.setPayeeVpa(payeeVpa);
        txn.setAmount(amount);
        txn.setRefundedAmount(refunded != null ? refunded : BigDecimal.ZERO);
        txn.setInitiatedAt(initiatedAt);
        txn.setStatus(status);
        return txn;
    }

    // =========================================================================
    // 1. Transaction Status Eligibility Tests (Section 1)
    // =========================================================================

    @Test
    @DisplayName("Disputes can only be raised against eligible transaction states")
    void testEligibilityAcrossTransactionStatuses() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 10, 10, 0, 0);

        // 1. SUCCESS: Eligible
        TransactionEntity successTxn = createMockTxn("TXN-SUCC", "user@upi", "payee@upi",
                new BigDecimal("1000.00"), BigDecimal.ZERO, now.minusDays(2), TransactionStatus.SUCCESS);
        when(transactionRepository.findByTxnId("TXN-SUCC")).thenReturn(Optional.of(successTxn));
        DisputeLogRow r1 = disputeService.raiseDispute(1, "DSP-S", now, "TXN-SUCC", "CHARGEBACK", "REMITTER", new BigDecimal("1000.00"));
        assertEquals("OPEN_CHARGEBACK", r1.resultState());

        // 2. DEEMED_SUCCESS: Eligible
        TransactionEntity deemedTxn = createMockTxn("TXN-DEEM", "user@upi", "payee@upi",
                new BigDecimal("1000.00"), BigDecimal.ZERO, now.minusDays(2), TransactionStatus.DEEMED_SUCCESS);
        when(transactionRepository.findByTxnId("TXN-DEEM")).thenReturn(Optional.of(deemedTxn));
        DisputeLogRow r2 = disputeService.raiseDispute(2, "DSP-D", now, "TXN-DEEM", "CHARGEBACK", "REMITTER", new BigDecimal("1000.00"));
        assertEquals("OPEN_CHARGEBACK", r2.resultState());

        // 3. FAILED: Ineligible
        TransactionEntity failedTxn = createMockTxn("TXN-FAIL", "user@upi", "payee@upi",
                new BigDecimal("1000.00"), BigDecimal.ZERO, now.minusDays(2), TransactionStatus.FAILED);
        when(transactionRepository.findByTxnId("TXN-FAIL")).thenReturn(Optional.of(failedTxn));
        DisputeLogRow r3 = disputeService.raiseDispute(3, "DSP-F", now, "TXN-FAIL", "CHARGEBACK", "REMITTER", new BigDecimal("1000.00"));
        assertEquals("REJECT", r3.resultState());
        assertEquals("INELIGIBLE_TXN_STATUS", r3.ruleIds());

        // 4. PENDING: Ineligible
        TransactionEntity pendingTxn = createMockTxn("TXN-PEND", "user@upi", "payee@upi",
                new BigDecimal("1000.00"), BigDecimal.ZERO, now.minusDays(2), TransactionStatus.PENDING);
        when(transactionRepository.findByTxnId("TXN-PEND")).thenReturn(Optional.of(pendingTxn));
        DisputeLogRow r4 = disputeService.raiseDispute(4, "DSP-P", now, "TXN-PEND", "CHARGEBACK", "REMITTER", new BigDecimal("1000.00"));
        assertEquals("REJECT", r4.resultState());
        assertEquals("INELIGIBLE_TXN_STATUS", r4.ruleIds());

        // 5. REVERSED: Ineligible under BR-11
        TransactionEntity revTxn = createMockTxn("TXN-REV", "user@upi", "payee@upi",
                new BigDecimal("1000.00"), BigDecimal.ZERO, now.minusDays(2), TransactionStatus.REVERSED);
        when(transactionRepository.findByTxnId("TXN-REV")).thenReturn(Optional.of(revTxn));
        DisputeLogRow r5 = disputeService.raiseDispute(5, "DSP-R", now, "TXN-REV", "CHARGEBACK", "REMITTER", new BigDecimal("1000.00"));
        assertEquals("REJECT", r5.resultState());
        assertEquals("BR-11", r5.ruleIds());

        // 6. RECON_SHORTFALL: Ineligible under BR-11
        TransactionEntity shortTxn = createMockTxn("TXN-SHORT", "user@upi", "payee@upi",
                new BigDecimal("1000.00"), BigDecimal.ZERO, now.minusDays(2), TransactionStatus.RECON_SHORTFALL);
        when(transactionRepository.findByTxnId("TXN-SHORT")).thenReturn(Optional.of(shortTxn));
        DisputeLogRow r6 = disputeService.raiseDispute(6, "DSP-SH", now, "TXN-SHORT", "CHARGEBACK", "REMITTER", new BigDecimal("1000.00"));
        assertEquals("REJECT", r6.resultState());
        assertEquals("BR-11", r6.ruleIds());

        // 7. Unknown Transaction: Rejected
        when(transactionRepository.findByTxnId("TXN-UNKNOWN")).thenReturn(Optional.empty());
        DisputeLogRow r7 = disputeService.raiseDispute(7, "DSP-U", now, "TXN-UNKNOWN", "CHARGEBACK", "REMITTER", new BigDecimal("1000.00"));
        assertEquals("REJECT", r7.resultState());
        assertEquals("INVALID_TXN", r7.ruleIds());

        // 8. Duplicate Dispute ID: Rejected
        DisputeEntity existing = new DisputeEntity();
        existing.setDisputeId("DSP-DUP");
        when(disputeRepository.findByDisputeId("DSP-DUP")).thenReturn(Optional.of(existing));
        DisputeLogRow r8 = disputeService.raiseDispute(8, "DSP-DUP", now, "TXN-SUCC", "CHARGEBACK", "REMITTER", new BigDecimal("1000.00"));
        assertEquals("REJECT", r8.resultState());
        assertEquals("DUPLICATE_DISPUTE_ID", r8.ruleIds());
    }

    // =========================================================================
    // 2. TCC Window Tests: T+5 and T+6 (BR-08)
    // =========================================================================

    @Test
    @DisplayName("TCC on T+5 calendar days is accepted into OPEN_TCC")
    void testTPlus5Valid() {
        TransactionEntity txn = createMockTxn("TXN-0024", "geetha308@ybl", "nandini@icici",
                new BigDecimal("980.00"), BigDecimal.ZERO,
                LocalDateTime.of(2026, 10, 13, 9, 0, 0), TransactionStatus.SUCCESS);
        when(transactionRepository.findByTxnId("TXN-0024")).thenReturn(Optional.of(txn));
        when(disputeRepository.findByTxnId("TXN-0024")).thenReturn(Collections.emptyList());

        // Dispute filed on 2026-10-18: exactly T+5 calendar days
        LocalDateTime disputeTime = LocalDateTime.of(2026, 10, 18, 16, 0, 0);
        DisputeLogRow row = disputeService.raiseDispute(
                75, "DSP-005", disputeTime, "TXN-0024", "TCC", "BENEFICIARY_BANK", new BigDecimal("980.00")
        );

        assertEquals("OPEN_TCC", row.resultState());
        assertEquals("", row.ruleIds());
        assertNotNull(row.deadline());
        verify(disputeRepository, times(1)).save(any(DisputeEntity.class));
    }

    @Test
    @DisplayName("TCC on T+6 calendar days is rejected under BR-08")
    void testTPlus6Invalid() {
        TransactionEntity txn = createMockTxn("TXN-0025", "geetha308@ybl", "nandini@icici",
                new BigDecimal("560.00"), BigDecimal.ZERO,
                LocalDateTime.of(2026, 10, 13, 10, 0, 0), TransactionStatus.SUCCESS);
        when(transactionRepository.findByTxnId("TXN-0025")).thenReturn(Optional.of(txn));
        when(disputeRepository.findByTxnId("TXN-0025")).thenReturn(Collections.emptyList());

        // Dispute filed on 2026-10-19: T+6 calendar days
        LocalDateTime disputeTime = LocalDateTime.of(2026, 10, 19, 10, 5, 0);
        DisputeLogRow row = disputeService.raiseDispute(
                78, "DSP-007", disputeTime, "TXN-0025", "TCC", "BENEFICIARY_BANK", new BigDecimal("560.00")
        );

        assertEquals("REJECT", row.resultState());
        assertEquals("BR-08", row.ruleIds());
        assertNull(row.deadline());
    }

    // =========================================================================
    // 3. Chargeback Window Tests: Day 45 and Day 46 (BR-09)
    // =========================================================================

    @Test
    @DisplayName("Chargeback on Day 45 is accepted into OPEN_CHARGEBACK (Day 45 inclusive)")
    void testChargebackDay45Valid() {
        // Oct 02 + 45 days: 29 days in Oct + 16 days in Nov = Nov 16
        TransactionEntity txn = createMockTxn("TXN-0006", "hema891@ibl", "sapna@icici",
                new BigDecimal("3200.00"), BigDecimal.ZERO,
                LocalDateTime.of(2026, 10, 2, 11, 0, 0), TransactionStatus.SUCCESS);
        when(transactionRepository.findByTxnId("TXN-0006")).thenReturn(Optional.of(txn));
        when(disputeRepository.findByTxnId("TXN-0006")).thenReturn(Collections.emptyList());

        LocalDateTime disputeTime = LocalDateTime.of(2026, 11, 16, 10, 0, 0);
        DisputeLogRow row = disputeService.raiseDispute(
                137, "DSP-002", disputeTime, "TXN-0006", "CHARGEBACK", "REMITTER", new BigDecimal("3200.00")
        );

        assertEquals("OPEN_CHARGEBACK", row.resultState());
        assertEquals("", row.ruleIds());
        assertNotNull(row.deadline());
    }

    @Test
    @DisplayName("Chargeback on Day 46 is rejected under BR-09")
    void testChargebackDay46Invalid() {
        // Oct 02 + 46 days = Nov 17
        TransactionEntity txn = createMockTxn("TXN-0007", "hema891@ibl", "sapna@icici",
                new BigDecimal("1800.00"), BigDecimal.ZERO,
                LocalDateTime.of(2026, 10, 2, 11, 15, 0), TransactionStatus.SUCCESS);
        when(transactionRepository.findByTxnId("TXN-0007")).thenReturn(Optional.of(txn));
        when(disputeRepository.findByTxnId("TXN-0007")).thenReturn(Collections.emptyList());

        LocalDateTime disputeTime = LocalDateTime.of(2026, 11, 17, 10, 0, 0);
        DisputeLogRow row = disputeService.raiseDispute(
                143, "DSP-003", disputeTime, "TXN-0007", "CHARGEBACK", "REMITTER", new BigDecimal("1800.00")
        );

        assertEquals("REJECT", row.resultState());
        assertEquals("BR-09", row.ruleIds());
    }

    // =========================================================================
    // 4. Section 4 Example: Exact Partial Refund & Exposure Testing
    // =========================================================================

    @Test
    @DisplayName("Verify exact Section 4 example: ₹10,000 txn with ₹2,000 and ₹1,000 refunds -> ₹7,000 exposure")
    void testSection4ExampleChargebackAndRefundExposure() {
        // Original: ₹10,000, Refunds: ₹2,000 + ₹1,000 = ₹3,000. Remaining: ₹7,000.
        TransactionEntity txn = createMockTxn("TXN-SEC4", "payer@upi", "payee@upi",
                new BigDecimal("10000.00"), new BigDecimal("3000.00"),
                LocalDateTime.of(2026, 10, 10, 10, 0, 0), TransactionStatus.SUCCESS);
        when(transactionRepository.findByTxnId("TXN-SEC4")).thenReturn(Optional.of(txn));
        when(disputeRepository.findByTxnId("TXN-SEC4")).thenReturn(Collections.emptyList());

        LocalDateTime evalTime = LocalDateTime.of(2026, 10, 15, 10, 0, 0);

        // 1. Chargeback of ₹7,000 (Exact remaining amount) -> ACCEPTED
        DisputeLogRow r1 = disputeService.raiseDispute(1, "DSP-7000", evalTime, "TXN-SEC4", "CHARGEBACK", "REMITTER", new BigDecimal("7000.00"));
        assertEquals("OPEN_CHARGEBACK", r1.resultState());

        // 2. Chargeback of ₹7,001 (Exceeds by ₹1) -> REJECTED (BR-09)
        DisputeLogRow r2 = disputeService.raiseDispute(2, "DSP-7001", evalTime, "TXN-SEC4", "CHARGEBACK", "REMITTER", new BigDecimal("7001.00"));
        assertEquals("REJECT", r2.resultState());
        assertEquals("BR-09", r2.ruleIds());

        // 3. Chargeback of ₹6,999 (Under by ₹1) -> ACCEPTED
        DisputeLogRow r3 = disputeService.raiseDispute(3, "DSP-6999", evalTime, "TXN-SEC4", "CHARGEBACK", "REMITTER", new BigDecimal("6999.00"));
        assertEquals("OPEN_CHARGEBACK", r3.resultState());

        // 4. Chargeback of ₹10,000 after ₹3,000 refunded -> REJECTED (BR-09)
        DisputeLogRow r4 = disputeService.raiseDispute(4, "DSP-10000", evalTime, "TXN-SEC4", "CHARGEBACK", "REMITTER", new BigDecimal("10000.00"));
        assertEquals("REJECT", r4.resultState());
        assertEquals("BR-09", r4.ruleIds());
    }

    // =========================================================================
    // 5. Dispute State Machine Validation (Section 5)
    // =========================================================================

    @Test
    @DisplayName("Verify permitted state machine transitions: RAISED->ACCEPTED, RAISED->REPRESENTED->PRE_ARB->CLOSED, ACCEPTED->CLOSED")
    void testPermittedStateTransitions() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 10, 0, 0);

        // Path A: RAISED -> ACCEPTED -> CLOSED
        DisputeEntity d1 = new DisputeEntity("DSP-P1", "TXN-1", DisputeType.CHARGEBACK, "REMITTER",
                new BigDecimal("500.00"), now, DisputeState.OPEN_CHARGEBACK, now.plusDays(5), null);
        when(disputeRepository.findByDisputeIdForUpdate("DSP-P1")).thenReturn(Optional.of(d1));

        DisputeLogRow r1 = disputeService.handleDisputeAction(1, "DSP-P1", now.plusDays(1), "ACCEPT");
        assertEquals("ACCEPTED", r1.resultState());

        DisputeLogRow r2 = disputeService.handleDisputeAction(2, "DSP-P1", now.plusDays(2), "CLOSE");
        assertEquals("CLOSED", r2.resultState());

        // Path B: RAISED -> REPRESENTED -> PRE_ARBITRATION -> CLOSED
        DisputeEntity d2 = new DisputeEntity("DSP-P2", "TXN-2", DisputeType.CHARGEBACK, "REMITTER",
                new BigDecimal("500.00"), now, DisputeState.OPEN_CHARGEBACK, now.plusDays(5), null);
        when(disputeRepository.findByDisputeIdForUpdate("DSP-P2")).thenReturn(Optional.of(d2));

        DisputeLogRow r3 = disputeService.handleDisputeAction(3, "DSP-P2", now.plusDays(1), "REPRESENT");
        assertEquals("REPRESENTED", r3.resultState());

        DisputeLogRow r4 = disputeService.handleDisputeAction(4, "DSP-P2", now.plusDays(2), "PRE_ARBITRATION");
        assertEquals("PRE_ARBITRATION", r4.resultState());

        DisputeLogRow r5 = disputeService.handleDisputeAction(5, "DSP-P2", now.plusDays(3), "CLOSE");
        assertEquals("CLOSED", r5.resultState());
    }

    @Test
    @DisplayName("Verify all illegal state machine transitions explicitly mandated in Section 5 are rejected")
    void testAllMandatedIllegalStateTransitions() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 15, 10, 0, 0);

        // 1. RAISED -> PRE_ARBITRATION (Illegal)
        DisputeEntity dOpen = new DisputeEntity("DSP-1", "TXN-1", DisputeType.CHARGEBACK, "REMITTER",
                new BigDecimal("500.00"), now, DisputeState.OPEN_CHARGEBACK, now.plusDays(5), null);
        when(disputeRepository.findByDisputeIdForUpdate("DSP-1")).thenReturn(Optional.of(dOpen));
        DisputeLogRow r1 = disputeService.handleDisputeAction(1, "DSP-1", now.plusDays(1), "PRE_ARBITRATION");
        assertEquals("REJECT", r1.resultState());
        assertEquals("BR-10", r1.ruleIds());

        // 2. REPRESENTED -> ACCEPTED (Illegal under Section 5)
        DisputeEntity dRep = new DisputeEntity("DSP-2", "TXN-2", DisputeType.CHARGEBACK, "REMITTER",
                new BigDecimal("500.00"), now, DisputeState.REPRESENTED, now.plusDays(7), null);
        when(disputeRepository.findByDisputeIdForUpdate("DSP-2")).thenReturn(Optional.of(dRep));
        DisputeLogRow r2 = disputeService.handleDisputeAction(2, "DSP-2", now.plusDays(1), "ACCEPT");
        assertEquals("REJECT", r2.resultState());
        assertEquals("BR-10", r2.ruleIds());

        // 3. ACCEPTED -> REPRESENTED (Illegal)
        DisputeEntity dAcc = new DisputeEntity("DSP-3", "TXN-3", DisputeType.CHARGEBACK, "REMITTER",
                new BigDecimal("500.00"), now, DisputeState.ACCEPTED, null, null);
        when(disputeRepository.findByDisputeIdForUpdate("DSP-3")).thenReturn(Optional.of(dAcc));
        DisputeLogRow r3 = disputeService.handleDisputeAction(3, "DSP-3", now.plusDays(1), "REPRESENT");
        assertEquals("REJECT", r3.resultState());
        assertEquals("BR-10", r3.ruleIds());

        // 4. ACCEPTED -> PRE_ARBITRATION (Illegal)
        DisputeLogRow r4 = disputeService.handleDisputeAction(4, "DSP-3", now.plusDays(1), "PRE_ARBITRATION");
        assertEquals("REJECT", r4.resultState());
        assertEquals("BR-10", r4.ruleIds());

        // 5. CLOSED -> RAISED / ACCEPTED / REPRESENTED / PRE_ARBITRATION (Illegal)
        DisputeEntity dClosed = new DisputeEntity("DSP-4", "TXN-4", DisputeType.CHARGEBACK, "REMITTER",
                new BigDecimal("500.00"), now, DisputeState.CLOSED, null, null);
        when(disputeRepository.findByDisputeIdForUpdate("DSP-4")).thenReturn(Optional.of(dClosed));
        assertEquals("REJECT", disputeService.handleDisputeAction(5, "DSP-4", now.plusDays(1), "ACCEPT").resultState());
        assertEquals("REJECT", disputeService.handleDisputeAction(6, "DSP-4", now.plusDays(1), "REPRESENT").resultState());
        assertEquals("REJECT", disputeService.handleDisputeAction(7, "DSP-4", now.plusDays(1), "PRE_ARBITRATION").resultState());

        // 6. PRE_ARBITRATION -> REPRESENTED (Illegal)
        DisputeEntity dPreArb = new DisputeEntity("DSP-5", "TXN-5", DisputeType.CHARGEBACK, "REMITTER",
                new BigDecimal("500.00"), now, DisputeState.PRE_ARBITRATION, null, null);
        when(disputeRepository.findByDisputeIdForUpdate("DSP-5")).thenReturn(Optional.of(dPreArb));
        DisputeLogRow r8 = disputeService.handleDisputeAction(8, "DSP-5", now.plusDays(1), "REPRESENT");
        assertEquals("REJECT", r8.resultState());
        assertEquals("BR-10", r8.ruleIds());
    }

    // =========================================================================
    // 6. TCC and Chargeback Precedence Tests (BR-11)
    // =========================================================================

    @Test
    @DisplayName("Open TCC blocks Chargeback (BR-11); Closed TCC allows Chargeback")
    void testTccBlockingLogic() {
        TransactionEntity txn = createMockTxn("TXN-0024", "geetha308@ybl", "nandini@icici",
                new BigDecimal("980.00"), BigDecimal.ZERO,
                LocalDateTime.of(2026, 10, 13, 9, 0, 0), TransactionStatus.SUCCESS);
        when(transactionRepository.findByTxnId("TXN-0024")).thenReturn(Optional.of(txn));

        // Open TCC blocks
        DisputeEntity openTcc = new DisputeEntity("DSP-005", "TXN-0024", DisputeType.TCC, "BENEFICIARY_BANK",
                new BigDecimal("980.00"), LocalDateTime.of(2026, 10, 18, 16, 0, 0),
                DisputeState.OPEN_TCC, LocalDateTime.of(2026, 10, 23, 23, 59, 59), null);
        when(disputeRepository.findByTxnId("TXN-0024")).thenReturn(List.of(openTcc));

        DisputeLogRow r1 = disputeService.raiseDispute(77, "DSP-006", LocalDateTime.of(2026, 10, 19, 10, 0, 0),
                "TXN-0024", "CHARGEBACK", "REMITTER", new BigDecimal("980.00"));
        assertEquals("REJECT", r1.resultState());
        assertEquals("BR-11", r1.ruleIds());

        // Closed TCC allows
        DisputeEntity closedTcc = new DisputeEntity("DSP-005", "TXN-0024", DisputeType.TCC, "BENEFICIARY_BANK",
                new BigDecimal("980.00"), LocalDateTime.of(2026, 10, 18, 16, 0, 0),
                DisputeState.CLOSED, null, null);
        when(disputeRepository.findByTxnId("TXN-0024")).thenReturn(List.of(closedTcc));

        DisputeLogRow r2 = disputeService.raiseDispute(85, "DSP-006B", LocalDateTime.of(2026, 10, 25, 10, 0, 0),
                "TXN-0024", "CHARGEBACK", "REMITTER", new BigDecimal("980.00"));
        assertEquals("OPEN_CHARGEBACK", r2.resultState());
    }

    // =========================================================================
    // 7. Representment & Working-Day SLA Tests (Section 6)
    // =========================================================================

    @Test
    @DisplayName("Representment on 5th working day with Deepavali holiday extension is valid")
    void testRepresentmentOnFifthWorkingDay() {
        LocalDateTime deadline = businessCalendarService.calculateRepresentmentDeadline(LocalDate.of(2026, 11, 2));
        assertEquals(LocalDateTime.of(2026, 11, 10, 23, 59, 59), deadline);

        DisputeEntity dispute = new DisputeEntity("DSP-004", "TXN-0036", DisputeType.CHARGEBACK, "REMITTER",
                new BigDecimal("2500.00"), LocalDateTime.of(2026, 11, 2, 10, 0, 0),
                DisputeState.OPEN_CHARGEBACK, deadline, null);
        when(disputeRepository.findByDisputeIdForUpdate("DSP-004")).thenReturn(Optional.of(dispute));

        DisputeLogRow row = disputeService.handleDisputeAction(126, "DSP-004", LocalDateTime.of(2026, 11, 10, 11, 0, 0), "REPRESENT");
        assertEquals("REPRESENTED", row.resultState());
        assertEquals(LocalDateTime.of(2026, 11, 17, 23, 59, 59), row.deadline());
    }

    @Test
    @DisplayName("Representment on Deepavali bank holiday prior to deadline is valid")
    void testRepresentmentOnBankHoliday() {
        LocalDateTime deadline = businessCalendarService.calculateRepresentmentDeadline(LocalDate.of(2026, 11, 2));
        DisputeEntity dispute = new DisputeEntity("DSP-004", "TXN-0036", DisputeType.CHARGEBACK, "REMITTER",
                new BigDecimal("2500.00"), LocalDateTime.of(2026, 11, 2, 10, 0, 0),
                DisputeState.OPEN_CHARGEBACK, deadline, null);
        when(disputeRepository.findByDisputeIdForUpdate("DSP-004")).thenReturn(Optional.of(dispute));

        DisputeLogRow row = disputeService.handleDisputeAction(125, "DSP-004", LocalDateTime.of(2026, 11, 9, 10, 0, 0), "REPRESENT");
        assertEquals("REPRESENTED", row.resultState());
    }

    @Test
    @DisplayName("Representment after 5 working days deadline is rejected under BR-10")
    void testRepresentmentAfterDeadlineRejected() {
        LocalDateTime deadline = LocalDateTime.of(2026, 10, 23, 23, 59, 59);
        DisputeEntity dispute = new DisputeEntity("DSP-008", "TXN-0026", DisputeType.CHARGEBACK, "REMITTER",
                new BigDecimal("1300.00"), LocalDateTime.of(2026, 10, 15, 9, 0, 0),
                DisputeState.OPEN_CHARGEBACK, deadline, null);
        when(disputeRepository.findByDisputeIdForUpdate("DSP-008")).thenReturn(Optional.of(dispute));

        DisputeLogRow row = disputeService.handleDisputeAction(104, "DSP-008", LocalDateTime.of(2026, 10, 27, 9, 0, 0), "REPRESENT");
        assertEquals("REJECT", row.resultState());
        assertEquals("BR-10", row.ruleIds());
    }

    @Test
    @DisplayName("Dispute auto-accepts upon 5-working-day expiry without representment")
    void testAutoAcceptanceOnExpiry() {
        LocalDateTime deadline = LocalDateTime.of(2026, 10, 23, 23, 59, 59);
        DisputeEntity dispute = new DisputeEntity("DSP-008", "TXN-0026", DisputeType.CHARGEBACK, "REMITTER",
                new BigDecimal("1300.00"), LocalDateTime.of(2026, 10, 15, 9, 0, 0),
                DisputeState.OPEN_CHARGEBACK, deadline, null);
        when(disputeRepository.findByDisputeIdForUpdate("DSP-008")).thenReturn(Optional.of(dispute));

        // Queried at Oct 27 -> state auto-accepts, subsequent representment rejected
        DisputeLogRow row = disputeService.handleDisputeAction(104, "DSP-008", LocalDateTime.of(2026, 10, 27, 9, 0, 0), "REPRESENT");
        assertEquals("REJECT", row.resultState());
        assertEquals("BR-10", row.ruleIds());
        assertEquals(DisputeState.AUTO_ACCEPTED, dispute.getStatus());
    }

    // =========================================================================
    // 8. Pre-Arbitration 7-Calendar-Day SLA Tests (Section 7)
    // =========================================================================

    @Test
    @DisplayName("Pre-arbitration on Day 7 is accepted; Day 8 is rejected under BR-10")
    void testPreArbitration7DaysBoundary() {
        LocalDateTime deadline = LocalDateTime.of(2026, 11, 17, 23, 59, 59);
        DisputeEntity dispute = new DisputeEntity("DSP-004", "TXN-0036", DisputeType.CHARGEBACK, "REMITTER",
                new BigDecimal("2500.00"), LocalDateTime.of(2026, 11, 2, 10, 0, 0),
                DisputeState.REPRESENTED, deadline, null);
        when(disputeRepository.findByDisputeIdForUpdate("DSP-004")).thenReturn(Optional.of(dispute));

        // Day 7: Nov 17 11:00:00 -> Accepted
        DisputeLogRow r1 = disputeService.handleDisputeAction(144, "DSP-004", LocalDateTime.of(2026, 11, 17, 11, 0, 0), "PRE_ARBITRATION");
        assertEquals("PRE_ARBITRATION", r1.resultState());

        // Reset to REPRESENTED to test Day 8 rejection
        dispute.setStatus(DisputeState.REPRESENTED);
        dispute.setDeadline(deadline);

        // Day 8: Nov 18 10:00:00 -> Rejected
        DisputeLogRow r2 = disputeService.handleDisputeAction(145, "DSP-004", LocalDateTime.of(2026, 11, 18, 10, 0, 0), "PRE_ARBITRATION");
        assertEquals("REJECT", r2.resultState());
        assertEquals("BR-10", r2.ruleIds());
    }

    // =========================================================================
    // 9. Financial Decoupling & Settlement Tests (Section 10)
    // =========================================================================

    @Test
    @DisplayName("Financial isolation: Workflow actions move ZERO funds; ACCEPT settles atomically")
    void testFinancialDecouplingAndSettlement() {
        TransactionEntity txn = createMockTxn("TXN-0027", "anjali374@paytm", "udupi@icici",
                new BigDecimal("1000.00"), new BigDecimal("300.00"),
                LocalDateTime.of(2026, 10, 16, 18, 0, 0), TransactionStatus.SUCCESS);
        when(transactionRepository.findByTxnId("TXN-0027")).thenReturn(Optional.of(txn));
        when(disputeRepository.findByTxnId("TXN-0027")).thenReturn(Collections.emptyList());

        // 1. Lodge moves zero money
        disputeService.raiseDispute(81, "DSP-010", LocalDateTime.of(2026, 10, 20, 11, 5, 0),
                "TXN-0027", "CHARGEBACK", "REMITTER", new BigDecimal("700.00"));
        verify(ledgerService, never()).recordEntry(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(accountRepository, never()).save(any());

        // 2. ACCEPT settles funds
        VpaEntity remitterVpa = new VpaEntity();
        remitterVpa.setVpa("anjali374@paytm");
        remitterVpa.setAccountId(10L);

        AccountEntity remitterAcc = new AccountEntity();
        remitterAcc.setAccountId(10L);
        remitterAcc.setBalance(new BigDecimal("5000.00"));

        VpaEntity payeeVpa = new VpaEntity();
        payeeVpa.setVpa("udupi@icici");
        payeeVpa.setAccountId(20L);

        AccountEntity payeeAcc = new AccountEntity();
        payeeAcc.setAccountId(20L);
        payeeAcc.setBalance(new BigDecimal("10000.00"));

        when(vpaRepository.findByVpa("anjali374@paytm")).thenReturn(Optional.of(remitterVpa));
        when(accountRepository.findById(10L)).thenReturn(Optional.of(remitterAcc));
        when(accountRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(remitterAcc));

        when(vpaRepository.findByVpa("udupi@icici")).thenReturn(Optional.of(payeeVpa));
        when(accountRepository.findById(20L)).thenReturn(Optional.of(payeeAcc));
        when(accountRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(payeeAcc));

        DisputeEntity dispute = new DisputeEntity("DSP-010", "TXN-0027", DisputeType.CHARGEBACK, "REMITTER",
                new BigDecimal("700.00"), LocalDateTime.of(2026, 10, 20, 11, 5, 0),
                DisputeState.OPEN_CHARGEBACK, LocalDateTime.of(2026, 10, 27, 23, 59, 59), null);
        when(disputeRepository.findByDisputeIdForUpdate("DSP-010")).thenReturn(Optional.of(dispute));

        LocalDateTime acceptTime = LocalDateTime.of(2026, 10, 22, 11, 0, 0);
        DisputeLogRow row = disputeService.handleDisputeAction(90, "DSP-010", acceptTime, "ACCEPT");
        assertEquals("ACCEPTED", row.resultState());

        // Verify balance updates
        assertEquals(new BigDecimal("9300.00"), payeeAcc.getBalance());
        assertEquals(new BigDecimal("5700.00"), remitterAcc.getBalance());

        // Verify balanced ledger legs: 1 DISPUTE_DEBIT, 1 DISPUTE_CREDIT
        verify(ledgerService, times(1)).recordEntry(
                eq("TXN-0027"), eq(20L), eq("udupi@icici"), eq(LegType.DISPUTE_DEBIT),
                eq(new BigDecimal("700.00")), isNull(), eq(new BigDecimal("9300.00")),
                eq(acceptTime), isNull(), any()
        );
        verify(ledgerService, times(1)).recordEntry(
                eq("TXN-0027"), eq(10L), eq("anjali374@paytm"), eq(LegType.DISPUTE_CREDIT),
                isNull(), eq(new BigDecimal("700.00")), eq(new BigDecimal("5700.00")),
                eq(acceptTime), isNull(), any()
        );
    }

    // =========================================================================
    // 10. Audit Trail Recording Verification (Section 11)
    // =========================================================================

    @Test
    @DisplayName("Audit trail records complete metadata for every dispute action")
    void testAuditTrailRecording() {
        TransactionEntity txn = createMockTxn("TXN-0024", "geetha308@ybl", "nandini@icici",
                new BigDecimal("980.00"), BigDecimal.ZERO,
                LocalDateTime.of(2026, 10, 13, 9, 0, 0), TransactionStatus.SUCCESS);
        when(transactionRepository.findByTxnId("TXN-0024")).thenReturn(Optional.of(txn));
        when(disputeRepository.findByTxnId("TXN-0024")).thenReturn(Collections.emptyList());

        disputeService.raiseDispute(75, "DSP-005", LocalDateTime.of(2026, 10, 18, 16, 0, 0),
                "TXN-0024", "TCC", "BENEFICIARY_BANK", new BigDecimal("980.00"));

        ArgumentCaptor<DisputeEventEntity> captor = ArgumentCaptor.forClass(DisputeEventEntity.class);
        verify(disputeEventRepository, times(1)).save(captor.capture());

        DisputeEventEntity event = captor.getValue();
        assertEquals("DSP-005", event.getDisputeId());
        assertEquals("TXN-0024", event.getTxnId());
        assertEquals(DisputeAction.LODGE, event.getAction());
        assertEquals("OPEN_TCC", event.getNewState());
        assertEquals("SUCCESS", event.getResult());
        assertNotNull(event.getDeadline());
    }
}
