package com.ps006.upiswitch.command;

import com.ps006.upiswitch.command.model.CommandType;
import com.ps006.upiswitch.command.model.ParsedCommand;
import com.ps006.upiswitch.command.parser.CommandParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CommandParserComprehensiveTest {

    private final CommandParser parser = new CommandParser();

    // =========================================================================
    // 1. POSITIVE TESTS FOR EVERY COMMAND TYPE
    // =========================================================================

    @Test
    @DisplayName("Verify parsing PAY command")
    void testParsePay() {
        String line = "PAY|TXN-0001|2026-10-01 09:10:00|rohit890@okaxis|aditya874@oksbi|8000.00|bill";
        Optional<ParsedCommand> opt = parser.parseLine(3, line);
        assertTrue(opt.isPresent());
        ParsedCommand cmd = opt.get();
        assertTrue(cmd.valid());
        assertEquals(CommandType.PAY, cmd.commandType());
        assertEquals("TXN-0001", cmd.txnId());
        assertEquals(LocalDateTime.of(2026, 10, 1, 9, 10, 0), cmd.timestamp());
        assertEquals("rohit890@okaxis", cmd.payerVpa());
        assertEquals("aditya874@oksbi", cmd.payeeVpa());
        assertEquals(new BigDecimal("8000.00"), cmd.amount());
        assertEquals("bill", cmd.remarks());
    }

    @Test
    @DisplayName("Verify parsing BENE_RESPONSE ACK, NACK, NO_RESPONSE")
    void testParseBeneResponses() {
        String ackLine = "BENE_RESPONSE|TXN-0001|2026-10-01 09:10:03|ACK";
        ParsedCommand ackCmd = parser.parseLine(4, ackLine).orElseThrow();
        assertTrue(ackCmd.valid());
        assertEquals("ACK", ackCmd.responseCode());

        String nackLine = "BENE_RESPONSE|TXN-0033|2026-10-25 13:00:04|NACK";
        ParsedCommand nackCmd = parser.parseLine(98, nackLine).orElseThrow();
        assertTrue(nackCmd.valid());
        assertEquals("NACK", nackCmd.responseCode());

        String noRespLine = "BENE_RESPONSE|TXN-0015|2026-10-08 10:00:02|NO_RESPONSE";
        ParsedCommand noRespCmd = parser.parseLine(34, noRespLine).orElseThrow();
        assertTrue(noRespCmd.valid());
        assertEquals("NO_RESPONSE", noRespCmd.responseCode());
    }

    @Test
    @DisplayName("Verify parsing TIMEOUT_JOB command")
    void testParseTimeoutJob() {
        String line = "TIMEOUT_JOB|2026-10-08 10:30:00";
        ParsedCommand cmd = parser.parseLine(35, line).orElseThrow();
        assertTrue(cmd.valid());
        assertEquals(CommandType.TIMEOUT_JOB, cmd.commandType());
        assertEquals(LocalDateTime.of(2026, 10, 8, 10, 30, 0), cmd.timestamp());
    }

    @Test
    @DisplayName("Verify parsing COLLECT command")
    void testParseCollect() {
        String line = "COLLECT|TXN-0019|2026-10-11 12:00:00|udupi@icici|geetha308@ybl|2000.00|30|false";
        ParsedCommand cmd = parser.parseLine(49, line).orElseThrow();
        assertTrue(cmd.valid());
        assertEquals(CommandType.COLLECT, cmd.commandType());
        assertEquals("TXN-0019", cmd.txnId());
        assertEquals("udupi@icici", cmd.payeeVpa());
        assertEquals("geetha308@ybl", cmd.payerVpa());
        assertEquals(new BigDecimal("2000.00"), cmd.amount());
        assertEquals(30, cmd.expiryMinutes());
        assertFalse(cmd.pinFlag());
    }

    @Test
    @DisplayName("Verify parsing COLLECT_APPROVE and COLLECT_DECLINE")
    void testParseCollectApproveAndDecline() {
        String approveLine = "COLLECT_APPROVE|TXN-0019|2026-10-11 12:05:00";
        ParsedCommand approveCmd = parser.parseLine(50, approveLine).orElseThrow();
        assertTrue(approveCmd.valid());
        assertEquals(CommandType.COLLECT_APPROVE, approveCmd.commandType());
        assertEquals("TXN-0019", approveCmd.txnId());

        String declineLine = "COLLECT_DECLINE|TXN-0023|2026-10-11 14:10:00";
        ParsedCommand declineCmd = parser.parseLine(56, declineLine).orElseThrow();
        assertTrue(declineCmd.valid());
        assertEquals(CommandType.COLLECT_DECLINE, declineCmd.commandType());
        assertEquals("TXN-0023", declineCmd.txnId());
    }

    @Test
    @DisplayName("Verify parsing REFUND command")
    void testParseRefund() {
        String line = "REFUND|TXN-0027|2026-10-17 10:00:00|300.00";
        ParsedCommand cmd = parser.parseLine(73, line).orElseThrow();
        assertTrue(cmd.valid());
        assertEquals(CommandType.REFUND, cmd.commandType());
        assertEquals("TXN-0027", cmd.txnId());
        assertEquals(new BigDecimal("300.00"), cmd.amount());
    }

    @Test
    @DisplayName("Verify parsing DISPUTE command")
    void testParseDispute() {
        String line = "DISPUTE|DSP-001|2026-10-12 10:00:00|TXN-0016|CHARGEBACK|REMITTER|450.00";
        ParsedCommand cmd = parser.parseLine(58, line).orElseThrow();
        assertTrue(cmd.valid());
        assertEquals(CommandType.DISPUTE, cmd.commandType());
        assertEquals("DSP-001", cmd.disputeId());
        assertEquals("TXN-0016", cmd.txnId());
        assertEquals("CHARGEBACK", cmd.disputeType());
        assertEquals("REMITTER", cmd.raisedBy());
        assertEquals(new BigDecimal("450.00"), cmd.amount());
    }

    @Test
    @DisplayName("Verify parsing DISPUTE_ACTION command")
    void testParseDisputeAction() {
        String line = "DISPUTE_ACTION|DSP-005|2026-10-21 10:00:00|ACCEPT";
        ParsedCommand cmd = parser.parseLine(83, line).orElseThrow();
        assertTrue(cmd.valid());
        assertEquals(CommandType.DISPUTE_ACTION, cmd.commandType());
        assertEquals("DSP-005", cmd.disputeId());
        assertEquals("ACCEPT", cmd.disputeAction());
    }

    @Test
    @DisplayName("Verify parsing STATEMENT command")
    void testParseStatement() {
        String line = "STATEMENT|rohit890@okaxis|2026-10-01|2026-11-19";
        ParsedCommand cmd = parser.parseLine(147, line).orElseThrow();
        assertTrue(cmd.valid());
        assertEquals(CommandType.STATEMENT, cmd.commandType());
        assertEquals("rohit890@okaxis", cmd.statementVpa());
        assertEquals(LocalDate.of(2026, 10, 1), cmd.startDate());
        assertEquals(LocalDate.of(2026, 11, 19), cmd.endDate());
    }

    // =========================================================================
    // 2. NEGATIVE TESTS FOR MALFORMED COMMANDS
    // =========================================================================

    @Test
    @DisplayName("Negative: Unknown command name is captured as invalid")
    void testUnknownCommand() {
        String line = "TRANSFER|TXN-9999|2026-10-01 10:00:00";
        ParsedCommand cmd = parser.parseLine(10, line).orElseThrow();
        assertFalse(cmd.valid());
        assertTrue(cmd.errorMessage().contains("Unknown command: TRANSFER"));
    }

    @Test
    @DisplayName("Negative: PAY with insufficient tokens")
    void testPayMissingTokens() {
        String line = "PAY|TXN-0001|2026-10-01 09:10:00|rohit@axis";
        ParsedCommand cmd = parser.parseLine(11, line).orElseThrow();
        assertFalse(cmd.valid());
        assertTrue(cmd.errorMessage().contains("Expected at least 6 tokens"));
    }

    @Test
    @DisplayName("Negative: PAY with invalid VPA (missing @)")
    void testPayInvalidVpa() {
        String line = "PAY|TXN-0001|2026-10-01 09:10:00|invalidvpa|aditya@sbi|500.00|test";
        ParsedCommand cmd = parser.parseLine(12, line).orElseThrow();
        assertFalse(cmd.valid());
        assertTrue(cmd.errorMessage().contains("must contain '@'"));
    }

    @Test
    @DisplayName("Negative: PAY with non-positive amount (0.00)")
    void testPayZeroAmount() {
        String line = "PAY|TXN-0001|2026-10-01 09:10:00|rohit@axis|aditya@sbi|0.00|test";
        ParsedCommand cmd = parser.parseLine(13, line).orElseThrow();
        assertFalse(cmd.valid());
        assertTrue(cmd.errorMessage().contains("must be strictly positive"));
    }

    @Test
    @DisplayName("Negative: PAY with negative amount (-50.00)")
    void testPayNegativeAmount() {
        String line = "PAY|TXN-0001|2026-10-01 09:10:00|rohit@axis|aditya@sbi|-50.00|test";
        ParsedCommand cmd = parser.parseLine(14, line).orElseThrow();
        assertFalse(cmd.valid());
        assertTrue(cmd.errorMessage().contains("must be strictly positive"));
    }

    @Test
    @DisplayName("Negative: PAY with invalid timestamp format")
    void testPayInvalidTimestamp() {
        String line = "PAY|TXN-0001|2026/10/01 09:10:00|rohit@axis|aditya@sbi|100.00|test";
        ParsedCommand cmd = parser.parseLine(15, line).orElseThrow();
        assertFalse(cmd.valid());
        assertTrue(cmd.errorMessage().contains("Invalid timestamp format"));
    }

    @Test
    @DisplayName("Negative: BENE_RESPONSE with invalid response code")
    void testInvalidBeneResponseCode() {
        String line = "BENE_RESPONSE|TXN-0001|2026-10-01 09:10:03|UNKNOWN_CODE";
        ParsedCommand cmd = parser.parseLine(16, line).orElseThrow();
        assertFalse(cmd.valid());
        assertTrue(cmd.errorMessage().contains("Invalid BENE_RESPONSE code"));
    }

    @Test
    @DisplayName("Negative: COLLECT with non-boolean pin_flag")
    void testCollectInvalidPinFlag() {
        String line = "COLLECT|TXN-0019|2026-10-11 12:00:00|udupi@icici|geetha@ybl|2000.00|30|maybe";
        ParsedCommand cmd = parser.parseLine(17, line).orElseThrow();
        assertFalse(cmd.valid());
        assertTrue(cmd.errorMessage().contains("Invalid boolean for pin_flag"));
    }

    @Test
    @DisplayName("Negative: STATEMENT with start_date after end_date")
    void testStatementInvalidDateRange() {
        String line = "STATEMENT|rohit@axis|2026-11-20|2026-10-01";
        ParsedCommand cmd = parser.parseLine(18, line).orElseThrow();
        assertFalse(cmd.valid());
        assertTrue(cmd.errorMessage().contains("cannot be after end_date"));
    }

    // =========================================================================
    // 3. PHYSICAL EXECUTION ORDER AND INVERSION TESTS
    // =========================================================================

    @Test
    @DisplayName("Verify physical line-order preservation and planted TXN-0038 out-of-order packet")
    void testPhysicalOrderPreservationAndTxn0038PacketInversion() throws IOException {
        Path commandsPath = Paths.get("sample-data", "T046-PS-006-sample-data", "commands.txt");
        List<ParsedCommand> commands = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(commandsPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                Optional<ParsedCommand> opt = parser.parseLine(lineNo, line);
                opt.ifPresent(commands::add);
            }
        }

        // 1. Confirm physical line numbers are strictly monotonically increasing
        for (int i = 0; i < commands.size() - 1; i++) {
            assertTrue(commands.get(i).lineNo() < commands.get(i + 1).lineNo(),
                    "Physical line numbers must strictly increase monotonically");
        }

        // 2. Locate line 115 and line 116
        ParsedCommand line115 = commands.stream().filter(c -> c.lineNo() == 115).findFirst().orElseThrow();
        ParsedCommand line116 = commands.stream().filter(c -> c.lineNo() == 116).findFirst().orElseThrow();

        assertEquals(CommandType.BENE_RESPONSE, line115.commandType());
        assertEquals("TXN-0038", line115.txnId());
        assertEquals(LocalDateTime.of(2026, 11, 5, 10, 20, 6), line115.timestamp());

        assertEquals(CommandType.PAY, line116.commandType());
        assertEquals("TXN-0038", line116.txnId());
        assertEquals(LocalDateTime.of(2026, 11, 5, 10, 20, 0), line116.timestamp());

        // 3. Confirm that line 115 precedes line 116 in physical sequence, despite having a LATER timestamp
        int index115 = commands.indexOf(line115);
        int index116 = commands.indexOf(line116);
        assertEquals(index115 + 1, index116, "Line 115 must immediately precede Line 116 in physical dispatch list");
        assertTrue(line115.timestamp().isAfter(line116.timestamp()),
                "Planted edge case: Line 115 timestamp (10:20:06) is after Line 116 timestamp (10:20:00)");
    }

    @Test
    @DisplayName("Verify parsing handles padded whitespace around delimiters")
    void testParseWhitespacePaddedLine() {
        String paddedLine = "  PAY  |  TXN-0001  |  2026-10-01 09:10:00  |  rohit890@okaxis  |  aditya874@oksbi  |  8000.00  |  bill  ";
        ParsedCommand cmd = parser.parseLine(5, paddedLine).orElseThrow();
        assertTrue(cmd.valid());
        assertEquals(CommandType.PAY, cmd.commandType());
        assertEquals("TXN-0001", cmd.txnId());
        assertEquals(LocalDateTime.of(2026, 10, 1, 9, 10, 0), cmd.timestamp());
        assertEquals("rohit890@okaxis", cmd.payerVpa());
        assertEquals("aditya874@oksbi", cmd.payeeVpa());
        assertEquals(new BigDecimal("8000.00"), cmd.amount());
        assertEquals("bill", cmd.remarks());
    }

    // =========================================================================
    // 4. INTEGRATION TEST OVER COMPLETE COMMANDS.TXT
    // =========================================================================

    @Test
    @DisplayName("Integration: Parse complete supplied commands.txt and print metrics")
    void testParseCompleteCommandsFile() throws IOException {
        Path commandsPath = Paths.get("sample-data", "T046-PS-006-sample-data", "commands.txt");
        assertTrue(Files.exists(commandsPath), "commands.txt must exist at: " + commandsPath.toAbsolutePath());

        int totalLines = 0;
        int commentOrBlankLines = 0;
        int totalExecutableCommands = 0;
        Map<CommandType, Integer> countByType = new EnumMap<>(CommandType.class);
        List<Integer> invalidCommandLines = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(commandsPath, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                totalLines++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    commentOrBlankLines++;
                    continue;
                }

                Optional<ParsedCommand> opt = parser.parseLine(lineNo, line);
                if (opt.isPresent()) {
                    totalExecutableCommands++;
                    ParsedCommand cmd = opt.get();
                    if (!cmd.valid()) {
                        invalidCommandLines.add(lineNo);
                    } else {
                        countByType.merge(cmd.commandType(), 1, Integer::sum);
                    }
                }
            }
        }

        // Print Required Metrics
        System.out.println("================================================================================");
        System.out.println("PS-006 COMMAND PARSER INTEGRATION TEST METRICS");
        System.out.println("================================================================================");
        System.out.println("Total Lines in File:            " + totalLines);
        System.out.println("Comment / Blank Lines:          " + commentOrBlankLines);
        System.out.println("Total Executable Commands:      " + totalExecutableCommands);
        System.out.println("Invalid Commands Count:         " + invalidCommandLines.size());
        if (!invalidCommandLines.isEmpty()) {
            System.out.println("Invalid Physical Line Numbers:  " + invalidCommandLines);
        } else {
            System.out.println("Invalid Physical Line Numbers:  None (All syntax valid)");
        }
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println("Breakdown by Command Type:");
        for (CommandType type : CommandType.values()) {
            System.out.printf("  %-16s: %d%n", type.name(), countByType.getOrDefault(type, 0));
        }
        System.out.println("================================================================================");

        // Assertions against authoritative README specifications
        assertEquals(158, totalLines, "commands.txt has exactly 158 physical lines");
        assertEquals(40, commentOrBlankLines, "commands.txt has exactly 40 comment lines (#)");
        assertEquals(118, totalExecutableCommands, "commands.txt has exactly 118 executable commands");
        assertEquals(0, invalidCommandLines.size(), "Sample dataset commands.txt contains 0 syntax-level malformed commands");

        // Verify exact command type counts from README section 5
        assertEquals(42, countByType.get(CommandType.PAY));
        assertEquals(35, countByType.get(CommandType.BENE_RESPONSE));
        assertEquals(3, countByType.get(CommandType.TIMEOUT_JOB));
        assertEquals(5, countByType.get(CommandType.COLLECT));
        assertEquals(2, countByType.get(CommandType.COLLECT_APPROVE));
        assertEquals(1, countByType.get(CommandType.COLLECT_DECLINE));
        assertEquals(1, countByType.get(CommandType.REFUND));
        assertEquals(10, countByType.get(CommandType.DISPUTE));
        assertEquals(7, countByType.get(CommandType.DISPUTE_ACTION));
        assertEquals(12, countByType.get(CommandType.STATEMENT));
    }
}

