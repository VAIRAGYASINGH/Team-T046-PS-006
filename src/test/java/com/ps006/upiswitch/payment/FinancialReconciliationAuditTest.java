package com.ps006.upiswitch.payment;

import com.ps006.upiswitch.account.entity.AccountEntity;
import com.ps006.upiswitch.account.repository.AccountRepository;
import com.ps006.upiswitch.command.dispatcher.CommandDispatcher;
import com.ps006.upiswitch.command.loader.MasterDataInitializer;
import com.ps006.upiswitch.command.model.ParsedCommand;
import com.ps006.upiswitch.command.parser.CommandParser;
import com.ps006.upiswitch.dispute.entity.DisputeEntity;
import com.ps006.upiswitch.dispute.model.DisputeState;
import com.ps006.upiswitch.dispute.repository.DisputeRepository;
import com.ps006.upiswitch.ledger.service.LedgerService;
import com.ps006.upiswitch.payment.entity.TransactionEntity;
import com.ps006.upiswitch.payment.model.TransactionStatus;
import com.ps006.upiswitch.payment.repository.TransactionRepository;
import com.ps006.upiswitch.vpa.entity.VpaEntity;
import com.ps006.upiswitch.vpa.repository.VpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 10A Forensic Financial Audit Regression Test.
 * Validates the exact mathematical reconciliation of all 16 participant accounts,
 * verifies the exact ₹4,200.00 in-flight escrow hold on TXN-0038,
 * verifies that TCC DSP-005 moves zero funds,
 * and confirms that no fictitious ₹420.00 adjustment exists.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class FinancialReconciliationAuditTest {

    @Autowired private MasterDataInitializer masterDataInitializer;
    @Autowired private CommandParser commandParser;
    @Autowired private CommandDispatcher commandDispatcher;
    @Autowired private AccountRepository accountRepository;
    @Autowired private VpaRepository vpaRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private DisputeRepository disputeRepository;
    @Autowired private LedgerService ledgerService;

    @Test
    @DisplayName("Forensic financial audit: verify exact account reconciliation without fabricated adjustments")
    void testForensicFinancialReconciliation() throws IOException {
        Path inputDir = Paths.get("sample-data", "T046-PS-006-sample-data");
        assertTrue(Files.exists(inputDir), "Sample data dir must exist");

        // 1. Initialize master data
        masterDataInitializer.initialize(inputDir);

        // 2. Dispatch all 118 commands
        Path commandsPath = inputDir.resolve("commands.txt");
        try (BufferedReader reader = Files.newBufferedReader(commandsPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                Optional<ParsedCommand> opt = commandParser.parseLine(lineNo, line);
                if (opt.isPresent() && opt.get().valid()) {
                    commandDispatcher.dispatch(opt.get());
                }
            }
        }

        // =====================================================================
        // 3. AUDIT FINDING 1: DSP-005 (TCC) moves ZERO funds
        // =====================================================================
        DisputeEntity dsp005 = disputeRepository.findByDisputeId("DSP-005").orElseThrow();
        assertEquals(DisputeState.ACCEPTED, dsp005.getStatus(), "DSP-005 must be ACCEPTED");
        assertEquals(new BigDecimal("980.00"), dsp005.getAmount(), "DSP-005 amount must be exactly 980.00, not 420.00");

        // Nandini Milk Parlour (MER-001) received TXN-0024 (980.00) + TXN-0025 (560.00) = 1540.00
        // DSP-005 TCC acceptance did NOT debit Nandini. Her closing balance must be exactly 901,540.00.
        VpaEntity nandiniVpa = vpaRepository.findByVpa("nandini@icici").orElseThrow();
        AccountEntity nandiniAccount = accountRepository.findById(nandiniVpa.getAccountId()).orElseThrow();
        assertEquals(new BigDecimal("901540.00"), nandiniAccount.getBalance(),
                "Nandini closing balance must be exactly 901,540.00 (not 900,560.00)");

        // Geetha (USR-009) paid TXN-0019 (2000) + TXN-0024 (980) + TXN-0025 (560) + TXN-0034 (640) + TXN-0039 (120) = 4300
        // Her closing balance must be exactly 115,700.00 (120,000 - 4300)
        VpaEntity geethaVpa = vpaRepository.findByVpa("geetha308@ybl").orElseThrow();
        AccountEntity geethaAccount = accountRepository.findById(geethaVpa.getAccountId()).orElseThrow();
        assertEquals(new BigDecimal("115700.00"), geethaAccount.getBalance(),
                "Geetha closing balance must be exactly 115,700.00");

        // =====================================================================
        // 4. AUDIT FINDING 2: TXN-0038 in-flight escrow hold of ₹4,200.00
        // =====================================================================
        TransactionEntity txn0038 = transactionRepository.findByTxnId("TXN-0038").orElseThrow();
        assertEquals(TransactionStatus.PENDING, txn0038.getStatus(), "TXN-0038 must be in PENDING status");
        assertEquals(new BigDecimal("4200.00"), txn0038.getAmount(), "TXN-0038 amount must be 4200.00");

        // Aditya Joshi (USR-007) debited 4,200.00 on TXN-0038
        VpaEntity adityaVpa = vpaRepository.findByVpa("aditya874@oksbi").orElseThrow();
        AccountEntity adityaAccount = accountRepository.findById(adityaVpa.getAccountId()).orElseThrow();
        assertEquals(new BigDecimal("403570.00"), adityaAccount.getBalance(),
                "Aditya closing balance must be exactly 403,570.00");

        // Beneficiary Hema (USR-010) was NOT credited on TXN-0038
        VpaEntity hemaVpa = vpaRepository.findByVpa("hema891@ibl").orElseThrow();
        AccountEntity hemaAccount = accountRepository.findById(hemaVpa.getAccountId()).orElseThrow();
        assertEquals(new BigDecimal("395950.00"), hemaAccount.getBalance(),
                "Hema closing balance must be exactly 395,950.00");

        // =====================================================================
        // 5. AUDIT FINDING 3: TXN-0017 reconciliation shortfall
        // =====================================================================
        TransactionEntity txn0017 = transactionRepository.findByTxnId("TXN-0017").orElseThrow();
        assertEquals(TransactionStatus.RECON_SHORTFALL, txn0017.getStatus(), "TXN-0017 must be RECON_SHORTFALL");
        assertEquals(new BigDecimal("1400.00"), txn0017.getAmount());

        // Arjun (USR-005) balance remains 2,050.00 with zero overdraft
        VpaEntity arjunVpa = vpaRepository.findByVpa("arjun598@paytm").orElseThrow();
        AccountEntity arjunAccount = accountRepository.findById(arjunVpa.getAccountId()).orElseThrow();
        assertEquals(new BigDecimal("2050.00"), arjunAccount.getBalance(),
                "Arjun closing balance must be exactly 2,050.00 without unauthorized overdraft");

        // =====================================================================
        // 6. MACRO VARIANCE PROOF: Net Participant Delta is EXACTLY -₹4,200.00
        // =====================================================================
        BigDecimal totalUserClosing = BigDecimal.ZERO;
        for (int i = 1; i <= 12; i++) {
            totalUserClosing = totalUserClosing.add(accountRepository.findById((long) i).orElseThrow().getBalance());
        }
        assertEquals(new BigDecimal("2668140.00"), totalUserClosing.setScale(2, RoundingMode.HALF_UP));

        BigDecimal totalMerchantClosing = BigDecimal.ZERO;
        for (int i = 13; i <= 16; i++) {
            totalMerchantClosing = totalMerchantClosing.add(accountRepository.findById((long) i).orElseThrow().getBalance());
        }
        assertEquals(new BigDecimal("3364160.00"), totalMerchantClosing.setScale(2, RoundingMode.HALF_UP));

        BigDecimal totalParticipantClosing = totalUserClosing.add(totalMerchantClosing);
        assertEquals(new BigDecimal("6032300.00"), totalParticipantClosing.setScale(2, RoundingMode.HALF_UP));

        BigDecimal totalOpening = new BigDecimal("6036500.00");
        BigDecimal netParticipantDelta = totalParticipantClosing.subtract(totalOpening);

        // The net participant delta must be EXACTLY -4200.00 (the TXN-0038 escrow hold)
        assertEquals(new BigDecimal("-4200.00"), netParticipantDelta.setScale(2, RoundingMode.HALF_UP),
                "Net participant delta must be EXACTLY -4200.00, fully accounted for by TXN-0038 escrow hold");
    }
}
