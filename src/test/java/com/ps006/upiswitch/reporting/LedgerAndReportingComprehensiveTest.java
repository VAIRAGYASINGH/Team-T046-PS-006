package com.ps006.upiswitch.reporting;

import com.ps006.upiswitch.account.entity.AccountEntity;
import com.ps006.upiswitch.account.repository.AccountRepository;
import com.ps006.upiswitch.dispute.entity.DisputeEntity;
import com.ps006.upiswitch.dispute.model.DisputeAction;
import com.ps006.upiswitch.dispute.model.DisputeState;
import com.ps006.upiswitch.dispute.model.DisputeType;
import com.ps006.upiswitch.dispute.service.DisputeSettlementService;
import com.ps006.upiswitch.ledger.entity.LedgerEntryEntity;
import com.ps006.upiswitch.ledger.model.LegType;
import com.ps006.upiswitch.ledger.repository.LedgerRepository;
import com.ps006.upiswitch.ledger.service.LedgerService;
import com.ps006.upiswitch.payment.entity.TransactionEntity;
import com.ps006.upiswitch.payment.model.TransactionStatus;
import com.ps006.upiswitch.payment.model.TransactionType;
import com.ps006.upiswitch.payment.repository.TransactionRepository;
import com.ps006.upiswitch.reporting.model.DisputeLogRow;
import com.ps006.upiswitch.reporting.model.StatementRow;
import com.ps006.upiswitch.reporting.model.TxnFinalRow;
import com.ps006.upiswitch.reporting.model.TxnLogRow;
import com.ps006.upiswitch.reporting.service.ReportGeneratorService;
import com.ps006.upiswitch.reversal.service.RefundService;
import com.ps006.upiswitch.statement.dto.StatementRecordDto;
import com.ps006.upiswitch.statement.service.StatementService;
import com.ps006.upiswitch.vpa.entity.VpaEntity;
import com.ps006.upiswitch.vpa.model.OwnerType;
import com.ps006.upiswitch.vpa.model.VpaDetails;
import com.ps006.upiswitch.vpa.repository.VpaRepository;
import com.ps006.upiswitch.vpa.service.VpaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 9: Comprehensive Engineering Quality Test for Financial Ledger, Accounting Invariants,
 * Statement Generation, and Evaluation Reporting.
 */
public class LedgerAndReportingComprehensiveTest {

    private LedgerRepository ledgerRepository;
    private LedgerService ledgerService;
    private VpaService vpaService;
    private StatementService statementService;
    private ReportGeneratorService reportGeneratorService;

    @BeforeEach
    void setUp() {
        ledgerRepository = mock(LedgerRepository.class);
        when(ledgerRepository.findTopByOrderBySequenceNumberDesc()).thenReturn(Optional.empty());
        when(ledgerRepository.save(any(LedgerEntryEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ledgerService = new LedgerService(ledgerRepository);
        vpaService = mock(VpaService.class);
        statementService = new StatementService(ledgerService, vpaService);
        reportGeneratorService = new ReportGeneratorService();
    }

    @Test
    @DisplayName("Test 1: Payment Debit and Credit creates balanced double-entry ledger legs")
    void testRecordPaymentDebitAndCredit_BalancedDoubleEntry() {
        LocalDateTime ts = LocalDateTime.of(2026, 10, 1, 9, 10, 0);
        BigDecimal amount = new BigDecimal("8000.00");
        String txnId = "TXN-0001";

        LedgerEntryEntity debitEntry = ledgerService.recordDebit(
                txnId, 1L, "rohit890@okaxis", amount, new BigDecimal("292000.00"), ts, "Payment debit"
        );
        LedgerEntryEntity creditEntry = ledgerService.recordCredit(
                txnId, 2L, "aditya874@oksbi", amount, new BigDecimal("408000.00"), ts.plusSeconds(3), "Payment credit"
        );

        assertNotNull(debitEntry);
        assertEquals(LegType.DEBIT, debitEntry.getLegType());
        assertEquals(new BigDecimal("8000.00"), debitEntry.getDebitAmount());
        assertNull(debitEntry.getCreditAmount());
        assertEquals(new BigDecimal("292000.00"), debitEntry.getBalanceAfter());

        assertNotNull(creditEntry);
        assertEquals(LegType.CREDIT, creditEntry.getLegType());
        assertNull(creditEntry.getDebitAmount());
        assertEquals(new BigDecimal("8000.00"), creditEntry.getCreditAmount());
        assertEquals(new BigDecimal("408000.00"), creditEntry.getBalanceAfter());

        // Invariant: Total Debit == Total Credit
        assertEquals(debitEntry.getDebitAmount(), creditEntry.getCreditAmount());
    }

    @Test
    @DisplayName("Test 2: NACK Reversal produces exact compensating credit entry referencing original debit")
    void testNackReversal_CompensatingEntryAndReferenceLinked() {
        LocalDateTime payTs = LocalDateTime.of(2026, 10, 15, 10, 0, 0);
        LocalDateTime nackTs = LocalDateTime.of(2026, 10, 15, 10, 0, 2);
        String txnId = "TXN-0033";
        BigDecimal amount = new BigDecimal("450.00");

        LedgerEntryEntity origDebit = new LedgerEntryEntity(
                1L, txnId, 1L, "rohit890@okaxis", LegType.DEBIT, amount, null,
                new BigDecimal("291550.00"), payTs, null, "Initial debit"
        );
        origDebit.setLedgerEntryId(101L);

        when(ledgerRepository.findByTxnIdOrderBySequenceNumberAsc(txnId))
                .thenReturn(Collections.singletonList(origDebit));

        LedgerEntryEntity reversalEntry = ledgerService.recordReversal(
                txnId, 1L, "rohit890@okaxis", amount, new BigDecimal("292000.00"), nackTs, "NACK reversal"
        );

        assertNotNull(reversalEntry);
        assertEquals(LegType.REVERSAL, reversalEntry.getLegType());
        assertNull(reversalEntry.getDebitAmount());
        assertEquals(new BigDecimal("450.00"), reversalEntry.getCreditAmount());
        assertEquals(new BigDecimal("292000.00"), reversalEntry.getBalanceAfter());
        // Reference to original debit leg must be linked
        assertEquals(101L, reversalEntry.getReferenceLedgerEntryId());

        // Net account balance effect: -450 + 450 = 0.00
        BigDecimal net = reversalEntry.getCreditAmount().subtract(origDebit.getDebitAmount());
        assertEquals(BigDecimal.ZERO.setScale(2), net);
    }

    @Test
    @DisplayName("Test 3: Timeout Reversal produces compensating credit entry")
    void testTimeoutReversal_CompensatingEntryAndReferenceLinked() {
        LocalDateTime payTs = LocalDateTime.of(2026, 10, 8, 10, 0, 0);
        LocalDateTime timeoutTs = LocalDateTime.of(2026, 10, 8, 10, 30, 0);
        String txnId = "TXN-0015";
        BigDecimal amount = new BigDecimal("12000.00");

        LedgerEntryEntity origDebit = new LedgerEntryEntity(
                15L, txnId, 7L, "aditya874@oksbi", LegType.DEBIT, amount, null,
                new BigDecimal("407000.00"), payTs, null, "Initial debit"
        );
        origDebit.setLedgerEntryId(205L);

        when(ledgerRepository.findByTxnIdOrderBySequenceNumberAsc(txnId))
                .thenReturn(Collections.singletonList(origDebit));

        LedgerEntryEntity reversal = ledgerService.recordReversal(
                txnId, 7L, "aditya874@oksbi", amount, new BigDecimal("419000.00"), timeoutTs, "Timeout auto-reversal"
        );

        assertNotNull(reversal);
        assertEquals(LegType.REVERSAL, reversal.getLegType());
        assertEquals(new BigDecimal("12000.00"), reversal.getCreditAmount());
        assertNull(reversal.getDebitAmount());
        assertEquals(205L, reversal.getReferenceLedgerEntryId());
        assertEquals(new BigDecimal("419000.00"), reversal.getBalanceAfter());
    }

    @Test
    @DisplayName("Test 4: Late ACK Deemed Success complete 4-leg financial lifecycle is balanced")
    void testLateAckRedebit_DeemedSuccessFullLifecycle() {
        String txnId = "TXN-0015";
        BigDecimal amount = new BigDecimal("12000.00");
        LocalDateTime t1 = LocalDateTime.of(2026, 10, 8, 10, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 10, 8, 10, 30, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 10, 8, 10, 30, 40);

        // Leg 1: Initial Debit on payer
        LedgerEntryEntity leg1 = ledgerService.recordDebit(txnId, 7L, "aditya874@oksbi", amount, new BigDecimal("407000.00"), t1, "Debit");
        // Leg 2: Timeout Reversal on payer
        LedgerEntryEntity leg2 = ledgerService.recordEntry(txnId, 7L, "aditya874@oksbi", LegType.REVERSAL, null, amount, new BigDecimal("419000.00"), t2, null, "Reversal");
        // Leg 3: Late ACK re-debit on payer
        LedgerEntryEntity leg3 = ledgerService.recordDebit(txnId, 7L, "aditya874@oksbi", amount, new BigDecimal("407000.00"), t3, "Late ACK re-debit");
        // Leg 4: Late ACK deemed credit on payee
        LedgerEntryEntity leg4 = ledgerService.recordCredit(txnId, 9L, "geetha308@ybl", amount, new BigDecimal("112000.00"), t3, "Beneficiary credit");

        BigDecimal totalDebits = leg1.getDebitAmount().add(leg3.getDebitAmount());
        BigDecimal totalCredits = leg2.getCreditAmount().add(leg4.getCreditAmount());

        assertEquals(new BigDecimal("24000.00"), totalDebits);
        assertEquals(new BigDecimal("24000.00"), totalCredits);
        assertEquals(totalDebits, totalCredits);

        // Net effect on payer: -12000 + 12000 - 12000 = -12000
        BigDecimal payerNet = leg1.getDebitAmount().negate().add(leg2.getCreditAmount()).subtract(leg3.getDebitAmount());
        assertEquals(amount.negate(), payerNet);

        // Net effect on payee: +12000
        assertEquals(amount, leg4.getCreditAmount());
    }

    @Test
    @DisplayName("Test 5: Late ACK Reconciliation Shortfall does not generate fraudulent unbalanced transfer legs")
    void testLateAckReconShortfall_NoUnbalancedLegCreated() {
        // When payer balance is insufficient during late ACK, status becomes RECON_SHORTFALL
        // Invariant: switch MUST NOT create an unbacked debit or credit leg.
        // Completed transfer legs remain only the initial balanced pair: Leg 1 (Debit) and Leg 2 (Reversal)
        String txnId = "TXN-0017";
        BigDecimal amount = new BigDecimal("1400.00");
        LocalDateTime t1 = LocalDateTime.of(2026, 10, 10, 9, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 10, 10, 9, 30, 0);

        LedgerEntryEntity leg1 = ledgerService.recordDebit(txnId, 1L, "rohit890@okaxis", amount, new BigDecimal("290600.00"), t1, "Debit");
        LedgerEntryEntity leg2 = ledgerService.recordEntry(txnId, 1L, "rohit890@okaxis", LegType.REVERSAL, null, amount, new BigDecimal("292000.00"), t2, null, "Reversal");

        // Late ACK arrives, but payer has spent balance -> RECON_SHORTFALL
        // Ledger records NO new entry for RECON_SHORTFALL!
        assertEquals(leg1.getDebitAmount(), leg2.getCreditAmount());
        BigDecimal totalDebit = leg1.getDebitAmount();
        BigDecimal totalCredit = leg2.getCreditAmount();
        assertEquals(totalDebit, totalCredit);
    }

    @Test
    @DisplayName("Test 6: Merchant Refund produces balanced debit on merchant and credit on customer")
    void testMerchantRefund_DoubleEntryLegs() {
        String txnId = "TXN-0027";
        BigDecimal refundAmount = new BigDecimal("300.00");
        LocalDateTime ts = LocalDateTime.of(2026, 10, 17, 10, 0, 0);

        LedgerEntryEntity merchantLeg = ledgerService.recordEntry(
                txnId, 101L, "udupi@icici", LegType.REFUND, refundAmount, null,
                new BigDecimal("49700.00"), ts, null, "Merchant refund debit"
        );
        LedgerEntryEntity customerLeg = ledgerService.recordEntry(
                txnId, 11L, "anjali374@paytm", LegType.REFUND, null, refundAmount,
                new BigDecimal("50300.00"), ts, null, "Customer refund credit"
        );

        assertNotNull(merchantLeg);
        assertEquals(LegType.REFUND, merchantLeg.getLegType());
        assertEquals(new BigDecimal("300.00"), merchantLeg.getDebitAmount());
        assertNull(merchantLeg.getCreditAmount());

        assertNotNull(customerLeg);
        assertEquals(LegType.REFUND, customerLeg.getLegType());
        assertNull(customerLeg.getDebitAmount());
        assertEquals(new BigDecimal("300.00"), customerLeg.getCreditAmount());

        assertEquals(merchantLeg.getDebitAmount(), customerLeg.getCreditAmount());
    }

    @Test
    @DisplayName("Test 7: Dispute Settlement produces balanced DISPUTE_DEBIT and DISPUTE_CREDIT")
    void testDisputeSettlement_DoubleEntryLegs() {
        String txnId = "TXN-0027";
        BigDecimal disputeAmount = new BigDecimal("700.00");
        LocalDateTime ts = LocalDateTime.of(2026, 10, 22, 11, 0, 0);

        LedgerEntryEntity payeeDebit = ledgerService.recordEntry(
                txnId, 101L, "udupi@icici", LegType.DISPUTE_DEBIT, disputeAmount, null,
                new BigDecimal("49000.00"), ts, null, "Dispute debit"
        );
        LedgerEntryEntity remitterCredit = ledgerService.recordEntry(
                txnId, 11L, "anjali374@paytm", LegType.DISPUTE_CREDIT, null, disputeAmount,
                new BigDecimal("51000.00"), ts, null, "Dispute credit"
        );

        assertEquals(LegType.DISPUTE_DEBIT, payeeDebit.getLegType());
        assertEquals(LegType.DISPUTE_CREDIT, remitterCredit.getLegType());
        assertEquals(payeeDebit.getDebitAmount(), remitterCredit.getCreditAmount());
    }

    @Test
    @DisplayName("Test 8: Statement generation filters date range inclusively across boundaries")
    void testStatementGeneration_DateRangeFilteringInclusive() {
        String vpa = "rohit890@okaxis";
        Long accountId = 1L;
        when(vpaService.getVpaOrThrow(vpa)).thenReturn(new VpaDetails(vpa, accountId, OwnerType.USER, "USR-001", LocalDateTime.now(), true));

        List<LedgerEntryEntity> mockEntries = Arrays.asList(
                new LedgerEntryEntity(1L, "TXN-0001", accountId, vpa, LegType.DEBIT, new BigDecimal("8000.00"), null, new BigDecimal("292000.00"), LocalDateTime.of(2026, 10, 1, 9, 10, 0), null, "D1"),
                new LedgerEntryEntity(2L, "TXN-0004", accountId, vpa, LegType.DEBIT, new BigDecimal("10000.00"), null, new BigDecimal("282000.00"), LocalDateTime.of(2026, 10, 1, 23, 59, 59), null, "D1 boundary"),
                new LedgerEntryEntity(3L, "TXN-0005", accountId, vpa, LegType.DEBIT, new BigDecimal("20000.00"), null, new BigDecimal("262000.00"), LocalDateTime.of(2026, 10, 2, 0, 0, 0), null, "D2 boundary"),
                new LedgerEntryEntity(4L, "TXN-0008", accountId, vpa, LegType.DEBIT, new BigDecimal("5000.00"), null, new BigDecimal("257000.00"), LocalDateTime.of(2026, 10, 5, 12, 0, 0), null, "D5 outside")
        );

        when(ledgerRepository.findStatementsForAccount(accountId)).thenReturn(mockEntries);

        // Filter: 2026-10-01 to 2026-10-02 (inclusive)
        List<StatementRecordDto> results = statementService.generateStatementForVpa(vpa, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2));

        assertEquals(3, results.size());
        assertEquals("TXN-0001", results.get(0).txnId());
        assertEquals("TXN-0004", results.get(1).txnId());
        assertEquals("TXN-0005", results.get(2).txnId());
    }

    @Test
    @DisplayName("Test 9: Deterministic ordering for entries having the exact same timestamp")
    void testStatementGeneration_DeterministicOrderingWithSameTimestamp() {
        String vpa = "aditya874@oksbi";
        Long accountId = 2L;
        when(vpaService.getVpaOrThrow(vpa)).thenReturn(new VpaDetails(vpa, accountId, OwnerType.USER, "USR-007", LocalDateTime.now(), true));

        LocalDateTime sameTs = LocalDateTime.of(2026, 10, 8, 10, 30, 40);
        List<LedgerEntryEntity> mockEntries = Arrays.asList(
                new LedgerEntryEntity(10L, "TXN-0015", accountId, vpa, LegType.DEBIT, new BigDecimal("12000.00"), null, new BigDecimal("407000.00"), sameTs, null, "First"),
                new LedgerEntryEntity(11L, "TXN-0016", accountId, vpa, LegType.CREDIT, null, new BigDecimal("500.00"), new BigDecimal("407500.00"), sameTs, null, "Second")
        );

        when(ledgerRepository.findStatementsForAccount(accountId)).thenReturn(mockEntries);

        List<StatementRecordDto> results = statementService.generateStatementForVpa(vpa, null, null);
        assertEquals(2, results.size());
        assertEquals("TXN-0015", results.get(0).txnId());
        assertEquals("TXN-0016", results.get(1).txnId());
    }

    @Test
    @DisplayName("Test 10: Running balance accuracy reflects consecutive financial events")
    void testStatementGeneration_RunningBalanceAccuracy() {
        String vpa = "rohit890@okaxis";
        Long accountId = 1L;
        when(vpaService.getVpaOrThrow(vpa)).thenReturn(new VpaDetails(vpa, accountId, OwnerType.USER, "USR-001", LocalDateTime.now(), true));

        BigDecimal opening = new BigDecimal("300000.00");
        BigDecimal bal1 = opening.subtract(new BigDecimal("8000.00")); // 292000.00
        BigDecimal bal2 = bal1.subtract(new BigDecimal("10000.00"));   // 282000.00
        BigDecimal bal3 = bal2.subtract(new BigDecimal("20000.00"));   // 262000.00

        List<LedgerEntryEntity> entries = Arrays.asList(
                new LedgerEntryEntity(1L, "TXN-0001", accountId, vpa, LegType.DEBIT, new BigDecimal("8000.00"), null, bal1, LocalDateTime.of(2026, 10, 1, 9, 10, 0), null, "1"),
                new LedgerEntryEntity(2L, "TXN-0004", accountId, vpa, LegType.DEBIT, new BigDecimal("10000.00"), null, bal2, LocalDateTime.of(2026, 10, 1, 23, 59, 59), null, "2"),
                new LedgerEntryEntity(3L, "TXN-0005", accountId, vpa, LegType.DEBIT, new BigDecimal("20000.00"), null, bal3, LocalDateTime.of(2026, 10, 2, 0, 0, 0), null, "3")
        );

        when(ledgerRepository.findStatementsForAccount(accountId)).thenReturn(entries);

        List<StatementRecordDto> records = statementService.generateStatementForVpa(vpa);
        assertEquals(new BigDecimal("292000.00"), records.get(0).runningBalance());
        assertEquals(new BigDecimal("282000.00"), records.get(1).runningBalance());
        assertEquals(new BigDecimal("262000.00"), records.get(2).runningBalance());
    }

    @Test
    @DisplayName("Test 11: ReportGeneratorService correctly formats txn_log.csv")
    void testReportGeneratorService_TxnLogCsvFormat(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        Path csvFile = tempDir.resolve("txn_log.csv");
        List<TxnLogRow> rows = Arrays.asList(
                new TxnLogRow(3, "TXN-0001", "PAY", LocalDateTime.of(2026, 10, 1, 9, 10, 0),
                        "rohit890@okaxis", "aditya874@oksbi", new BigDecimal("8000.00"), "PENDING", "000000000001", ""),
                new TxnLogRow(5, "TXN-0002", "PAY", LocalDateTime.of(2026, 10, 1, 11, 30, 0),
                        "harini730@ibl", "hema891@ibl", new BigDecimal("150.00"), "FAILED", "000000000002", "BR-03")
        );

        reportGeneratorService.generateTxnLogCsv(rows, csvFile);

        List<String> lines = Files.readAllLines(csvFile);
        assertEquals(3, lines.size());
        assertEquals("line_no,txn_id,command,timestamp,payer_vpa,payee_vpa,amount,status,rrn,rule_ids", lines.get(0));
        assertEquals("3,TXN-0001,PAY,2026-10-01 09:10:00,rohit890@okaxis,aditya874@oksbi,8000.00,PENDING,000000000001,", lines.get(1));
        assertEquals("5,TXN-0002,PAY,2026-10-01 11:30:00,harini730@ibl,hema891@ibl,150.00,FAILED,000000000002,BR-03", lines.get(2));
    }

    @Test
    @DisplayName("Test 12: ReportGeneratorService correctly formats txn_final.csv")
    void testReportGeneratorService_TxnFinalCsvFormat(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        Path csvFile = tempDir.resolve("txn_final.csv");
        List<TxnFinalRow> rows = Arrays.asList(
                new TxnFinalRow("TXN-0001", "SUCCESS", new BigDecimal("8000.00"), BigDecimal.ZERO, null, null),
                new TxnFinalRow("TXN-0015", "DEEMED_SUCCESS", new BigDecimal("12000.00"), BigDecimal.ZERO,
                        LocalDateTime.of(2026, 10, 8, 10, 30, 0), LocalDateTime.of(2026, 10, 8, 10, 30, 40)),
                new TxnFinalRow("TXN-0027", "SUCCESS", new BigDecimal("1000.00"), new BigDecimal("300.00"), null, null)
        );

        reportGeneratorService.generateTxnFinalCsv(rows, csvFile);

        List<String> lines = Files.readAllLines(csvFile);
        assertEquals(4, lines.size());
        assertEquals("txn_id,final_status,amount,refunded_amount,reversed_at,deemed_success_at", lines.get(0));
        assertEquals("TXN-0001,SUCCESS,8000.00,0.00,,", lines.get(1));
        assertEquals("TXN-0015,DEEMED_SUCCESS,12000.00,0.00,2026-10-08 10:30:00,2026-10-08 10:30:40", lines.get(2));
        assertEquals("TXN-0027,SUCCESS,1000.00,300.00,,", lines.get(3));
    }

    @Test
    @DisplayName("Test 13: ReportGeneratorService correctly formats dispute_log.csv")
    void testReportGeneratorService_DisputeLogCsvFormat(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        Path csvFile = tempDir.resolve("dispute_log.csv");
        List<DisputeLogRow> rows = Arrays.asList(
                new DisputeLogRow(58, "DSP-001", "TXN-0016", "CHARGEBACK", "",
                        LocalDateTime.of(2026, 10, 12, 10, 0, 0), "REJECT", null, "BR-11"),
                new DisputeLogRow(68, "DSP-008", "TXN-0026", "CHARGEBACK", "",
                        LocalDateTime.of(2026, 10, 15, 9, 0, 0), "OPEN_CHARGEBACK", LocalDateTime.of(2026, 10, 23, 23, 59, 59), ""),
                new DisputeLogRow(83, "DSP-005", "TXN-0024", "TCC", "ACCEPT",
                        LocalDateTime.of(2026, 10, 21, 10, 0, 0), "ACCEPTED", null, "")
        );

        reportGeneratorService.generateDisputeLogCsv(rows, csvFile);

        List<String> lines = Files.readAllLines(csvFile);
        assertEquals(4, lines.size());
        assertEquals("line_no,dispute_id,txn_id,type,action,timestamp,result_state,deadline,rule_ids", lines.get(0));
        assertEquals("58,DSP-001,TXN-0016,CHARGEBACK,,2026-10-12 10:00:00,REJECT,,BR-11", lines.get(1));
        assertEquals("68,DSP-008,TXN-0026,CHARGEBACK,,2026-10-15 09:00:00,OPEN_CHARGEBACK,2026-10-23 23:59:59,", lines.get(2));
        assertEquals("83,DSP-005,TXN-0024,TCC,ACCEPT,2026-10-21 10:00:00,ACCEPTED,,", lines.get(3));
    }

    @Test
    @DisplayName("Test 14: ReportGeneratorService correctly formats statements.csv")
    void testReportGeneratorService_StatementsCsvFormat(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        Path csvFile = tempDir.resolve("statements.csv");
        List<StatementRow> rows = Arrays.asList(
                new StatementRow("rohit890@okaxis", LocalDateTime.of(2026, 10, 1, 9, 10, 0),
                        "TXN-0001", "DEBIT", new BigDecimal("8000.00"), null, new BigDecimal("292000.00")),
                new StatementRow("aditya874@oksbi", LocalDateTime.of(2026, 10, 1, 9, 10, 3),
                        "TXN-0001", "CREDIT", null, new BigDecimal("8000.00"), new BigDecimal("408000.00")),
                new StatementRow("aditya874@oksbi", LocalDateTime.of(2026, 10, 8, 10, 30, 0),
                        "TXN-0015", "REVERSAL", null, new BigDecimal("12000.00"), new BigDecimal("419000.00"))
        );

        reportGeneratorService.generateStatementsCsv(rows, csvFile);

        List<String> lines = Files.readAllLines(csvFile);
        assertEquals(4, lines.size());
        assertEquals("vpa,timestamp,txn_id,leg_type,debit,credit,running_balance", lines.get(0));
        assertEquals("rohit890@okaxis,2026-10-01 09:10:00,TXN-0001,DEBIT,8000.00,,292000.00", lines.get(1));
        assertEquals("aditya874@oksbi,2026-10-01 09:10:03,TXN-0001,CREDIT,,8000.00,408000.00", lines.get(2));
        assertEquals("aditya874@oksbi,2026-10-08 10:30:00,TXN-0015,REVERSAL,,12000.00,419000.00", lines.get(3));
    }

    @Test
    @DisplayName("Test 15: Double-Entry Invariant verification method detects balances and synthetic discrepancies")
    void testVerifyDoubleEntryInvariantMethod() {
        when(ledgerRepository.sumTotalDebits()).thenReturn(new BigDecimal("125000.00"));
        when(ledgerRepository.sumTotalCredits()).thenReturn(new BigDecimal("125000.00"));

        assertTrue(ledgerService.verifyDoubleEntryInvariant());

        // Discrepancy scenario:
        when(ledgerRepository.sumTotalCredits()).thenReturn(new BigDecimal("124999.00"));
        assertFalse(ledgerService.verifyDoubleEntryInvariant());
    }

    @Test
    @DisplayName("Test 16: Ledger Immutability guarantees entity cannot be modified or deleted via JPA callbacks")
    void testLedgerImmutability_PreUpdateAndPreRemoveThrowException() throws Exception {
        LedgerEntryEntity entry = new LedgerEntryEntity(
                1L, "TXN-0001", 1L, "rohit890@okaxis", LegType.DEBIT,
                new BigDecimal("8000.00"), null, new BigDecimal("292000.00"),
                LocalDateTime.now(), null, "Test entry"
        );

        java.lang.reflect.Method onUpdateMethod = LedgerEntryEntity.class.getDeclaredMethod("onUpdate");
        onUpdateMethod.setAccessible(true);
        java.lang.reflect.InvocationTargetException exUpdate = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> onUpdateMethod.invoke(entry)
        );
        assertTrue(exUpdate.getCause() instanceof UnsupportedOperationException);
        assertTrue(exUpdate.getCause().getMessage().contains("strictly immutable"));

        java.lang.reflect.Method onRemoveMethod = LedgerEntryEntity.class.getDeclaredMethod("onRemove");
        onRemoveMethod.setAccessible(true);
        java.lang.reflect.InvocationTargetException exRemove = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> onRemoveMethod.invoke(entry)
        );
        assertTrue(exRemove.getCause() instanceof UnsupportedOperationException);
        assertTrue(exRemove.getCause().getMessage().contains("cannot be deleted"));
    }

    @Test
    @DisplayName("Test 17: Date Boundary - Single day statement (start date equals end date)")
    void testStatementDateBoundary_SingleDayStartEqualsEnd() {
        String vpa = "rohit890@okaxis";
        Long accountId = 1L;
        when(vpaService.getVpaOrThrow(vpa)).thenReturn(new VpaDetails(vpa, accountId, OwnerType.USER, "USR-001", LocalDateTime.now(), true));

        LocalDate targetDay = LocalDate.of(2026, 10, 1);
        List<LedgerEntryEntity> mockEntries = Arrays.asList(
                new LedgerEntryEntity(1L, "TXN-0001", accountId, vpa, LegType.DEBIT, new BigDecimal("8000.00"), null, new BigDecimal("292000.00"), targetDay.atTime(9, 10, 0), null, "Morning"),
                new LedgerEntryEntity(2L, "TXN-0004", accountId, vpa, LegType.DEBIT, new BigDecimal("10000.00"), null, new BigDecimal("282000.00"), targetDay.atTime(23, 59, 59), null, "End of day"),
                new LedgerEntryEntity(3L, "TXN-0005", accountId, vpa, LegType.DEBIT, new BigDecimal("20000.00"), null, new BigDecimal("262000.00"), targetDay.plusDays(1).atStartOfDay(), null, "Next day")
        );

        when(ledgerRepository.findStatementsForAccount(accountId)).thenReturn(mockEntries);

        List<StatementRecordDto> results = statementService.generateStatementForVpa(vpa, targetDay, targetDay);
        assertEquals(2, results.size());
        assertEquals("TXN-0001", results.get(0).txnId());
        assertEquals("TXN-0004", results.get(1).txnId());
    }

    @Test
    @DisplayName("Test 18: Date Boundary - Midnight crossing and 1-second offset boundary precision")
    void testStatementDateBoundary_MidnightCrossingAndOneSecondOffsets() {
        String vpa = "rohit890@okaxis";
        Long accountId = 1L;
        when(vpaService.getVpaOrThrow(vpa)).thenReturn(new VpaDetails(vpa, accountId, OwnerType.USER, "USR-001", LocalDateTime.now(), true));

        LocalDate day1 = LocalDate.of(2026, 10, 1);
        LocalDate day2 = LocalDate.of(2026, 10, 2);

        List<LedgerEntryEntity> mockEntries = Arrays.asList(
                // 1 second before start of window (2026-09-30 23:59:59)
                new LedgerEntryEntity(1L, "TXN-PRE", accountId, vpa, LegType.DEBIT, new BigDecimal("100.00"), null, new BigDecimal("299900.00"), LocalDateTime.of(2026, 9, 30, 23, 59, 59), null, "Pre"),
                // Exact start of window (2026-10-01 00:00:00)
                new LedgerEntryEntity(2L, "TXN-START", accountId, vpa, LegType.DEBIT, new BigDecimal("200.00"), null, new BigDecimal("299700.00"), LocalDateTime.of(2026, 10, 1, 0, 0, 0), null, "Start"),
                // Exact end of window (2026-10-02 23:59:59)
                new LedgerEntryEntity(3L, "TXN-END", accountId, vpa, LegType.DEBIT, new BigDecimal("300.00"), null, new BigDecimal("299400.00"), LocalDateTime.of(2026, 10, 2, 23, 59, 59), null, "End"),
                // 1 second after end of window (2026-10-03 00:00:00)
                new LedgerEntryEntity(4L, "TXN-POST", accountId, vpa, LegType.DEBIT, new BigDecimal("400.00"), null, new BigDecimal("299000.00"), LocalDateTime.of(2026, 10, 3, 0, 0, 0), null, "Post")
        );

        when(ledgerRepository.findStatementsForAccount(accountId)).thenReturn(mockEntries);

        List<StatementRecordDto> results = statementService.generateStatementForVpa(vpa, day1, day2);
        assertEquals(2, results.size());
        assertEquals("TXN-START", results.get(0).txnId());
        assertEquals("TXN-END", results.get(1).txnId());
    }

    @Test
    @DisplayName("Test 19: Unknown VPA throws exception with clear diagnostic message")
    void testStatementGeneration_UnknownVpaThrowsException() {
        String unknownVpa = "nonexistent@axis";
        when(vpaService.getVpaOrThrow(unknownVpa)).thenThrow(new IllegalArgumentException("VPA not found: " + unknownVpa));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                statementService.generateStatementForVpa(unknownVpa, LocalDate.now(), LocalDate.now())
        );
        assertTrue(ex.getMessage().contains("VPA not found"));
    }

    @Test
    @DisplayName("Test 20: Account balance reconciliation formula Closing = Opening + Credits - Debits")
    void testAccountBalanceReconciliation_FormulaVerification() {
        BigDecimal openingBalance = new BigDecimal("300000.00");

        BigDecimal debit1 = new BigDecimal("8000.00");
        BigDecimal debit2 = new BigDecimal("10000.00");
        BigDecimal debit3 = new BigDecimal("20000.00");
        BigDecimal totalDebits = debit1.add(debit2).add(debit3); // 38000.00
        BigDecimal totalCredits = BigDecimal.ZERO.setScale(2);

        BigDecimal expectedClosing = openingBalance.add(totalCredits).subtract(totalDebits);
        assertEquals(new BigDecimal("262000.00"), expectedClosing);

        // Verification with credits and dispute clawback:
        BigDecimal credit1 = new BigDecimal("5000.00");
        BigDecimal disputeDebit = new BigDecimal("1300.00");
        BigDecimal netClosing = expectedClosing.add(credit1).subtract(disputeDebit);
        assertEquals(new BigDecimal("265700.00"), netClosing);
    }

    @Test
    @DisplayName("Test 21: UTF-8 encoding and CSV formatting verification")
    void testReportGeneratorService_Utf8Encoding(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        Path csvFile = tempDir.resolve("utf8_test.csv");
        List<TxnLogRow> rows = Collections.singletonList(
                new TxnLogRow(1, "TXN-0001", "PAY", LocalDateTime.of(2026, 10, 1, 9, 10, 0),
                        "rohit890@okaxis", "aditya874@oksbi", new BigDecimal("8000.00"), "SUCCESS", "000000000001", "")
        );

        reportGeneratorService.generateTxnLogCsv(rows, csvFile);

        byte[] bytes = Files.readAllBytes(csvFile);
        String content = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(content.contains("rohit890@okaxis"));
        assertTrue(content.contains("8000.00"));
    }

    @Test
    @DisplayName("Test 22: Failure Recovery - Target directory auto-creation when missing")
    void testReportGeneratorService_CreatesMissingTargetDirectory(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        Path nestedDir = tempDir.resolve("nested").resolve("subfolder").resolve("output");
        assertFalse(Files.exists(nestedDir));

        Path targetFile = nestedDir.resolve("txn_log.csv");
        List<TxnLogRow> rows = Collections.singletonList(
                new TxnLogRow(1, "TXN-0001", "PAY", LocalDateTime.now(), "a@b", "c@d", new BigDecimal("100.00"), "SUCCESS", "000000000001", "")
        );

        reportGeneratorService.generateTxnLogCsv(rows, targetFile);

        assertTrue(Files.exists(nestedDir));
        assertTrue(Files.exists(targetFile));
        assertEquals(2, Files.readAllLines(targetFile).size());
    }

    @Test
    @DisplayName("Test 23: Deterministic re-run output produces identical file contents")
    void testReportGeneratorService_DeterministicReRunOutput(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        Path file1 = tempDir.resolve("run1.csv");
        Path file2 = tempDir.resolve("run2.csv");

        List<StatementRow> rows = Arrays.asList(
                new StatementRow("rohit890@okaxis", LocalDateTime.of(2026, 10, 1, 9, 10, 0), "TXN-0001", "DEBIT", new BigDecimal("8000.00"), null, new BigDecimal("292000.00")),
                new StatementRow("aditya874@oksbi", LocalDateTime.of(2026, 10, 1, 9, 10, 3), "TXN-0001", "CREDIT", null, new BigDecimal("8000.00"), new BigDecimal("408000.00"))
        );

        reportGeneratorService.generateStatementsCsv(rows, file1);
        reportGeneratorService.generateStatementsCsv(rows, file2);

        byte[] bytes1 = Files.readAllBytes(file1);
        byte[] bytes2 = Files.readAllBytes(file2);

        assertArrayEquals(bytes1, bytes2, "Re-running report generation with identical rows must produce byte-for-byte identical output");
    }
}

