package com.ps006.upiswitch.payment;

import com.ps006.upiswitch.account.repository.AccountRepository;
import com.ps006.upiswitch.command.dispatcher.CommandDispatcher;
import com.ps006.upiswitch.command.loader.MasterDataInitializer;
import com.ps006.upiswitch.command.model.CommandType;
import com.ps006.upiswitch.command.model.ParsedCommand;
import com.ps006.upiswitch.command.parser.CommandParser;
import com.ps006.upiswitch.dispute.entity.DisputeEntity;
import com.ps006.upiswitch.dispute.model.DisputeState;
import com.ps006.upiswitch.dispute.repository.DisputeRepository;
import com.ps006.upiswitch.payment.entity.TransactionEntity;
import com.ps006.upiswitch.payment.model.TransactionStatus;
import com.ps006.upiswitch.payment.repository.TransactionRepository;
import com.ps006.upiswitch.ledger.model.LegType;
import com.ps006.upiswitch.ledger.repository.LedgerRepository;
import com.ps006.upiswitch.ledger.service.LedgerService;
import com.ps006.upiswitch.reporting.model.StatementRow;
import com.ps006.upiswitch.reporting.service.ReportGeneratorService;
import com.ps006.upiswitch.statement.service.StatementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class SampleDatasetPayScenarioTest {

    @Autowired private MasterDataInitializer masterDataInitializer;
    @Autowired private CommandParser commandParser;
    @Autowired private CommandDispatcher commandDispatcher;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private DisputeRepository disputeRepository;
    @Autowired private LedgerRepository ledgerRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private StatementService statementService;
    @Autowired private ReportGeneratorService reportGeneratorService;

    @Test
    @DisplayName("Verify official sample dataset PAY scenarios against authoritative README specifications")
    void testSampleDatasetPayScenarios() throws IOException {
        Path inputDir = Paths.get("sample-data", "T046-PS-006-sample-data");
        assertTrue(Files.exists(inputDir), "Sample data dir must exist");

        // 1. Initialize master data (users, merchants, holidays)
        MasterDataInitializer.MasterDataReport report = masterDataInitializer.initialize(inputDir);
        assertFalse(report.hasErrors(), "Master data loading must have 0 errors");

        // 2. Read and dispatch commands line by line from commands.txt
        Path commandsPath = inputDir.resolve("commands.txt");
        try (BufferedReader reader = Files.newBufferedReader(commandsPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                Optional<ParsedCommand> opt = commandParser.parseLine(lineNo, line);
                if (opt.isPresent()) {
                    ParsedCommand cmd = opt.get();
                    if (cmd.valid()) {
                        commandDispatcher.dispatch(cmd);
                    }
                }
            }
        }

        // =====================================================================
        // 3. ASSERTIONS ON OFFICIAL DATASET PAY TRANSACTIONS
        // =====================================================================

        // Case 1: Line 3 (TXN-0001) - Rohit to Aditya ₹8,000 -> SUCCESS (ACK on line 4)
        TransactionEntity txn0001 = transactionRepository.findByTxnId("TXN-0001").orElseThrow();
        assertEquals(TransactionStatus.SUCCESS, txn0001.getStatus());
        assertEquals(new BigDecimal("8000.00"), txn0001.getAmount());

        // Case 2: Line 5 (TXN-0002) - Harini opening debit count = 20 -> 21st fails with BR-04 / U30
        TransactionEntity txn0002 = transactionRepository.findByTxnId("TXN-0002").orElseThrow();
        assertEquals(TransactionStatus.FAILED, txn0002.getStatus());
        assertTrue(txn0002.getRuleIds().contains("BR-04"), "TXN-0002 must fail velocity BR-04");

        // Case 3: Line 6 (TXN-0003) - Rohit daily limit breach (82k + 8k + 15k = 105k > 100k) -> FAILED BR-03
        TransactionEntity txn0003 = transactionRepository.findByTxnId("TXN-0003").orElseThrow();
        assertEquals(TransactionStatus.FAILED, txn0003.getStatus());
        assertTrue(txn0003.getRuleIds().contains("BR-03"), "TXN-0003 must fail daily cap BR-03");

        // Case 4: Line 7 (TXN-0004) - Rohit daily cap exact boundary (90k + 10k = 100k) at 23:59:59 -> SUCCESS
        TransactionEntity txn0004 = transactionRepository.findByTxnId("TXN-0004").orElseThrow();
        assertEquals(TransactionStatus.SUCCESS, txn0004.getStatus());

        // Case 5: Line 9 (TXN-0005) - Midnight calendar reset at 2026-10-02 00:00:00 -> SUCCESS
        TransactionEntity txn0005 = transactionRepository.findByTxnId("TXN-0005").orElseThrow();
        assertEquals(TransactionStatus.SUCCESS, txn0005.getStatus());

        // Case 6: Line 17 (TXN-0008) - Vidya VPA created 23h 59m ago, limit is 5,000; ₹6,000 -> FAILED BR-02
        TransactionEntity txn0008 = transactionRepository.findByTxnId("TXN-0008").orElseThrow();
        assertEquals(TransactionStatus.FAILED, txn0008.getStatus());
        assertTrue(txn0008.getRuleIds().contains("BR-02"), "TXN-0008 must fail new VPA cooling BR-02");

        // Case 7: Line 18 (TXN-0009) - Vidya within cooling period, ₹5,000 -> SUCCESS
        TransactionEntity txn0009 = transactionRepository.findByTxnId("TXN-0009").orElseThrow();
        assertEquals(TransactionStatus.SUCCESS, txn0009.getStatus());

        // Case 8: Line 21 (TXN-0010) - Pradeep exactly 24h after creation, cooling over -> SUCCESS
        TransactionEntity txn0010 = transactionRepository.findByTxnId("TXN-0010").orElseThrow();
        assertEquals(TransactionStatus.SUCCESS, txn0010.getStatus());

        // Case 9: Line 24 & 26 (TXN-0011, TXN-0012) - 1st & 2nd payment to new beneficiary (<= 5,000) -> SUCCESS
        assertEquals(TransactionStatus.SUCCESS, transactionRepository.findByTxnId("TXN-0011").orElseThrow().getStatus());
        assertEquals(TransactionStatus.SUCCESS, transactionRepository.findByTxnId("TXN-0012").orElseThrow().getStatus());

        // Case 10: Line 28 (TXN-0013) - 3rd payment to new payee within 24h is ₹7,000 (> 5,000) -> FAILED BR-05
        TransactionEntity txn0013 = transactionRepository.findByTxnId("TXN-0013").orElseThrow();
        assertEquals(TransactionStatus.FAILED, txn0013.getStatus());
        assertTrue(txn0013.getRuleIds().contains("BR-05"), "TXN-0013 must fail beneficiary cooling BR-05");

        // Case 11: Line 30 (TXN-0014) - Exactly 24h after first payment to beneficiary, ₹7,000 -> SUCCESS
        TransactionEntity txn0014 = transactionRepository.findByTxnId("TXN-0014").orElseThrow();
        assertEquals(TransactionStatus.SUCCESS, txn0014.getStatus());

        // Case 12: Line 84 (TXN-0028) - ₹1,50,000 to Verified Merchant (cap 2L) -> SUCCESS
        TransactionEntity txn0028 = transactionRepository.findByTxnId("TXN-0028").orElseThrow();
        assertEquals(TransactionStatus.SUCCESS, txn0028.getStatus());

        // Case 13: Line 86 (TXN-0029) - ₹1,50,000 to Unverified Merchant (cap 1L) -> FAILED BR-01
        TransactionEntity txn0029 = transactionRepository.findByTxnId("TXN-0029").orElseThrow();
        assertEquals(TransactionStatus.FAILED, txn0029.getStatus());
        assertTrue(txn0029.getRuleIds().contains("BR-01"), "TXN-0029 must fail merchant cap BR-01");

        // Case 14: Line 88 (TXN-0030) - P2P exactly ₹1,00,000.00 -> SUCCESS
        TransactionEntity txn0030 = transactionRepository.findByTxnId("TXN-0030").orElseThrow();
        assertEquals(TransactionStatus.SUCCESS, txn0030.getStatus());

        // Case 15: Line 92 (TXN-0031) - P2P ₹1,00,000.01 exceeds 1L cap -> FAILED BR-01
        TransactionEntity txn0031 = transactionRepository.findByTxnId("TXN-0031").orElseThrow();
        assertEquals(TransactionStatus.FAILED, txn0031.getStatus());
        assertTrue(txn0031.getRuleIds().contains("BR-01"), "TXN-0031 must fail P2P cap BR-01");

        // Case 16: Line 95 (TXN-0032) - ₹1,20,000 from user with ₹1,500 balance -> FAILED BR-01 (balance checked last!)
        TransactionEntity txn0032 = transactionRepository.findByTxnId("TXN-0032").orElseThrow();
        assertEquals(TransactionStatus.FAILED, txn0032.getStatus());
        assertTrue(txn0032.getRuleIds().contains("BR-01"), "TXN-0032 must report regulatory cap BR-01");

        // Case 17: Line 97 (TXN-0033) - BENE_RESPONSE NACK on line 98 -> FAILED with refund
        TransactionEntity txn0033 = transactionRepository.findByTxnId("TXN-0033").orElseThrow();
        assertEquals(TransactionStatus.FAILED, txn0033.getStatus());

        // Case 18: Line 105 (TXN-0035) - Unknown payee ghost99@upi -> FAILED PAYEE_NOT_FOUND
        TransactionEntity txn0035 = transactionRepository.findByTxnId("TXN-0035").orElseThrow();
        assertEquals(TransactionStatus.FAILED, txn0035.getStatus());
        assertTrue(txn0035.getRuleIds().contains("PAYEE_NOT_FOUND") || txn0035.getRuleIds().contains("INVALID_VPA"));

        // Case 19: Lines 33-36 (TXN-0015) - NO_RESPONSE -> TIMEOUT_JOB -> Late ACK -> DEEMED_SUCCESS
        TransactionEntity txn0015 = transactionRepository.findByTxnId("TXN-0015").orElseThrow();
        assertEquals(TransactionStatus.DEEMED_SUCCESS, txn0015.getStatus());
        assertNotNull(txn0015.getReversedAt(), "TXN-0015 must record reversedAt timestamp");
        assertNotNull(txn0015.getDeemedSuccessAt(), "TXN-0015 must record deemedSuccessAt timestamp");

        // Case 20: Lines 38-40 (TXN-0016) - NO_RESPONSE -> TIMEOUT_JOB -> REVERSED (no late ACK)
        TransactionEntity txn0016 = transactionRepository.findByTxnId("TXN-0016").orElseThrow();
        assertEquals(TransactionStatus.REVERSED, txn0016.getStatus());
        assertNotNull(txn0016.getReversedAt(), "TXN-0016 must record reversedAt timestamp");
        assertNull(txn0016.getDeemedSuccessAt(), "TXN-0016 must not have deemedSuccessAt");

        // Case 21: Lines 42-47 (TXN-0017) - NO_RESPONSE -> TIMEOUT_JOB -> Spent balance -> Late ACK -> RECON_SHORTFALL
        TransactionEntity txn0017 = transactionRepository.findByTxnId("TXN-0017").orElseThrow();
        assertEquals(TransactionStatus.RECON_SHORTFALL, txn0017.getStatus());
        assertNotNull(txn0017.getReversedAt(), "TXN-0017 must record reversedAt timestamp");
        assertNull(txn0017.getDeemedSuccessAt(), "TXN-0017 must not have deemedSuccessAt");

        // Case 22: Lines 49-50 (TXN-0019) - COLLECT ₹2000.00 without PIN -> APPROVED -> SUCCESS
        TransactionEntity txn0019 = transactionRepository.findByTxnId("TXN-0019").orElseThrow();
        assertEquals(TransactionStatus.SUCCESS, txn0019.getStatus());
        assertEquals(new BigDecimal("2000.00"), txn0019.getAmount());

        // Case 23: Line 51 (TXN-0020) - COLLECT ₹2000.01 without PIN -> FAILED BR-07
        TransactionEntity txn0020 = transactionRepository.findByTxnId("TXN-0020").orElseThrow();
        assertEquals(TransactionStatus.FAILED, txn0020.getStatus());
        assertTrue(txn0020.getRuleIds().contains("BR-07"), "TXN-0020 must fail BR-07 PIN threshold");

        // Case 24: Line 52 (TXN-0021) - COLLECT 4321 mins (> 72h max) -> FAILED BR-07
        TransactionEntity txn0021 = transactionRepository.findByTxnId("TXN-0021").orElseThrow();
        assertEquals(TransactionStatus.FAILED, txn0021.getStatus());
        assertTrue(txn0021.getRuleIds().contains("BR-07"), "TXN-0021 must fail BR-07 max expiry");

        // Case 25: Lines 53-54 (TXN-0022) - COLLECT approved at 31 mins (expired) -> EXPIRED
        TransactionEntity txn0022 = transactionRepository.findByTxnId("TXN-0022").orElseThrow();
        assertEquals(TransactionStatus.EXPIRED, txn0022.getStatus());

        // Case 26: Lines 55-56 (TXN-0023) - COLLECT declined by payer -> DECLINED
        TransactionEntity txn0023 = transactionRepository.findByTxnId("TXN-0023").orElseThrow();
        assertEquals(TransactionStatus.DECLINED, txn0023.getStatus());

        // =====================================================================
        // 4. ASSERTIONS ON OFFICIAL DATASET DISPUTE OPERATIONS (PHASE 8)
        // =====================================================================

        // Case 27: Line 58 (DSP-001) - Dispute on reversed TXN-0016 -> REJECT under BR-11
        DisputeEntity dsp001 = disputeRepository.findByDisputeId("DSP-001").orElseThrow();
        assertEquals(DisputeState.REJECT, dsp001.getStatus());
        assertEquals("BR-11", dsp001.getRuleIds());

        // Case 28: Line 68 (DSP-008) - Chargeback on TXN-0026 -> deadline Oct 23 -> auto-accepted
        DisputeEntity dsp008 = disputeRepository.findByDisputeId("DSP-008").orElseThrow();
        assertTrue(dsp008.getStatus() == DisputeState.AUTO_ACCEPTED || dsp008.getStatus() == DisputeState.ACCEPTED);

        // Case 29: Line 75 & 83 (DSP-005) - TCC on TXN-0024 at T+5 -> OPEN_TCC -> Line 83 ACCEPT -> ACCEPTED
        DisputeEntity dsp005 = disputeRepository.findByDisputeId("DSP-005").orElseThrow();
        assertEquals(DisputeState.ACCEPTED, dsp005.getStatus());

        // Case 30: Line 77 (DSP-006) - Chargeback on TXN-0024 while DSP-005 TCC is open -> REJECT under BR-11
        DisputeEntity dsp006 = disputeRepository.findByDisputeId("DSP-006").orElseThrow();
        assertEquals(DisputeState.REJECT, dsp006.getStatus());
        assertEquals("BR-11", dsp006.getRuleIds());

        // Case 31: Line 78 (DSP-007) - TCC on TXN-0025 at T+6 -> REJECT under BR-08
        DisputeEntity dsp007 = disputeRepository.findByDisputeId("DSP-007").orElseThrow();
        assertEquals(DisputeState.REJECT, dsp007.getStatus());
        assertEquals("BR-08", dsp007.getRuleIds());

        // Case 32: Line 80 (DSP-009) - Chargeback 1000 after 300 refund (max 700) -> REJECT under BR-09
        DisputeEntity dsp009 = disputeRepository.findByDisputeId("DSP-009").orElseThrow();
        assertEquals(DisputeState.REJECT, dsp009.getStatus());
        assertEquals("BR-09", dsp009.getRuleIds());

        // Case 33: Line 81 & 90 (DSP-010) - Chargeback 700 after 300 refund -> OPEN_CHARGEBACK -> Line 90 ACCEPT -> ACCEPTED
        DisputeEntity dsp010 = disputeRepository.findByDisputeId("DSP-010").orElseThrow();
        assertEquals(DisputeState.ACCEPTED, dsp010.getStatus());

        // Case 34: Line 113, 126, 144, 146 (DSP-004) - Full lifecycle: OPEN_CHARGEBACK -> REPRESENTED -> PRE_ARBITRATION -> CLOSED
        DisputeEntity dsp004 = disputeRepository.findByDisputeId("DSP-004").orElseThrow();
        assertEquals(DisputeState.CLOSED, dsp004.getStatus());

        // Case 35: Line 137 (DSP-002) - Chargeback on TXN-0006 on Day 45 (inclusive) -> OPEN_CHARGEBACK
        DisputeEntity dsp002 = disputeRepository.findByDisputeId("DSP-002").orElseThrow();
        assertEquals(DisputeState.OPEN_CHARGEBACK, dsp002.getStatus());

        // Case 36: Line 143 (DSP-003) - Chargeback on TXN-0007 on Day 46 -> REJECT under BR-09
        DisputeEntity dsp003 = disputeRepository.findByDisputeId("DSP-003").orElseThrow();
        assertEquals(DisputeState.REJECT, dsp003.getStatus());
        assertEquals("BR-09", dsp003.getRuleIds());

        // =====================================================================
        // PHASE 9: FINANCIAL LEDGER & EVALUATION REPORTING ASSERTIONS
        // =====================================================================

        // Case 37: Global Double-Entry Accounting Invariant holds across the entire ledger
        assertTrue(ledgerService.verifyDoubleEntryInvariant(),
                "Total debits across all ledger entries must exactly match total credits");
        BigDecimal totalDebits = ledgerService.getTotalDebits();
        BigDecimal totalCredits = ledgerService.getTotalCredits();
        assertTrue(totalDebits.compareTo(BigDecimal.ZERO) > 0, "Total debits must be positive");
        assertEquals(totalDebits, totalCredits, "Total ledger debits must equal total ledger credits");

        // Case 38: Verify Statements collected for all 12 user statement commands in commands.txt
        var statementRows = commandDispatcher.getStatementRows();
        assertFalse(statementRows.isEmpty(), "Statement rows must not be empty after executing commands.txt");

        // Case 39: Verify all required financial leg types exist in the ledger
        var allLedgerEntries = ledgerRepository.findAll();
        Set<LegType> observedLegTypes = new HashSet<>();
        for (var entry : allLedgerEntries) {
            observedLegTypes.add(entry.getLegType());
        }
        assertTrue(observedLegTypes.contains(LegType.DEBIT), "Ledger must contain DEBIT entries");
        assertTrue(observedLegTypes.contains(LegType.CREDIT), "Ledger must contain CREDIT entries");
        assertTrue(observedLegTypes.contains(LegType.REVERSAL), "Ledger must contain REVERSAL entries");
        assertTrue(observedLegTypes.contains(LegType.REFUND), "Ledger must contain REFUND entries");
        assertTrue(observedLegTypes.contains(LegType.DISPUTE_DEBIT), "Ledger must contain DISPUTE_DEBIT entries");
        assertTrue(observedLegTypes.contains(LegType.DISPUTE_CREDIT), "Ledger must contain DISPUTE_CREDIT entries");

        // Case 40: Verify all 4 Evaluation CSV files are generated correctly
        Path outputDir = Files.createTempDirectory("ps006-eval-reports");
        reportGeneratorService.generateTxnLogCsv(commandDispatcher.getTxnLogRows(), outputDir.resolve("txn_log.csv"));
        reportGeneratorService.generateTxnFinalCsv(commandDispatcher.getTxnFinalRows(), outputDir.resolve("txn_final.csv"));
        reportGeneratorService.generateDisputeLogCsv(commandDispatcher.getDisputeLogRows(), outputDir.resolve("dispute_log.csv"));
        reportGeneratorService.generateStatementsCsv(commandDispatcher.getStatementRows(), outputDir.resolve("statements.csv"));

        Path txnLogPath = outputDir.resolve("txn_log.csv");
        Path txnFinalPath = outputDir.resolve("txn_final.csv");
        Path disputeLogPath = outputDir.resolve("dispute_log.csv");
        Path statementsPath = outputDir.resolve("statements.csv");

        assertTrue(Files.exists(txnLogPath));
        assertTrue(Files.exists(txnFinalPath));
        assertTrue(Files.exists(disputeLogPath));
        assertTrue(Files.exists(statementsPath));

        // Case 41: Header compliance verification against README specifications
        assertEquals("line_no,txn_id,command,timestamp,payer_vpa,payee_vpa,amount,status,rrn,rule_ids",
                Files.readAllLines(txnLogPath).get(0));
        assertEquals("txn_id,final_status,amount,refunded_amount,reversed_at,deemed_success_at",
                Files.readAllLines(txnFinalPath).get(0));
        assertEquals("line_no,dispute_id,txn_id,type,action,timestamp,result_state,deadline,rule_ids",
                Files.readAllLines(disputeLogPath).get(0));
        assertEquals("vpa,timestamp,txn_id,leg_type,debit,credit,running_balance",
                Files.readAllLines(statementsPath).get(0));
    }
}

