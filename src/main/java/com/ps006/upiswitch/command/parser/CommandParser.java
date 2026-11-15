package com.ps006.upiswitch.command.parser;

import com.ps006.upiswitch.command.model.CommandType;
import com.ps006.upiswitch.command.model.ParsedCommand;
import com.ps006.upiswitch.common.util.DateTimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;

@Component
public class CommandParser {

    private static final Logger log = LoggerFactory.getLogger(CommandParser.class);

    private static final Set<String> ALLOWED_BENE_RESPONSES = Set.of("ACK", "NACK", "NO_RESPONSE");
    private static final Set<String> ALLOWED_DISPUTE_TYPES = Set.of("TCC", "CHARGEBACK");
    private static final Set<String> ALLOWED_RAISED_BY = Set.of("REMITTER", "BENEFICIARY_BANK");
    private static final Set<String> ALLOWED_DISPUTE_ACTIONS = Set.of("ACCEPT", "REPRESENT", "PRE_ARBITRATION", "CLOSE");

    public Optional<ParsedCommand> parseLine(int lineNo, String rawLine) {
        if (rawLine == null) return Optional.empty();
        String trimmed = rawLine.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return Optional.empty();
        }

        String[] parts = trimmed.split("\\|", -1);
        if (parts.length == 0 || parts[0].trim().isEmpty()) {
            return Optional.of(ParsedCommand.invalid(lineNo, trimmed, null, "Empty command name"));
        }

        String commandName = parts[0].trim();
        CommandType type;
        try {
            type = CommandType.valueOf(commandName);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown command '{}' at line {}", commandName, lineNo);
            return Optional.of(ParsedCommand.invalid(lineNo, trimmed, null, "Unknown command: " + commandName));
        }

        try {
            ParsedCommand cmd = parseCommandByType(lineNo, trimmed, type, parts);
            return Optional.of(cmd);
        } catch (Exception e) {
            log.warn("Malformed command at line {}: {}", lineNo, e.getMessage());
            return Optional.of(ParsedCommand.invalid(lineNo, trimmed, type, e.getMessage()));
        }
    }

    public ParsedCommand parseOrThrow(int lineNo, String rawLine) throws CommandParseException {
        Optional<ParsedCommand> opt = parseLine(lineNo, rawLine);
        if (opt.isEmpty()) {
            throw new CommandParseException(lineNo, rawLine, "Empty or comment line");
        }
        ParsedCommand cmd = opt.get();
        if (!cmd.valid()) {
            throw new CommandParseException(lineNo, rawLine, cmd.errorMessage());
        }
        return cmd;
    }

    private ParsedCommand parseCommandByType(int lineNo, String rawLine, CommandType type, String[] parts) {
        ParsedCommand.Builder builder = ParsedCommand.builder(lineNo, rawLine, type);

        switch (type) {
            case PAY -> {
                // PAY|txn_id|timestamp|payer_vpa|payee_vpa|amount|remarks
                if (parts.length < 6) {
                    throw new IllegalArgumentException(String.format("Expected at least 6 tokens for PAY, found %d", parts.length));
                }
                String txnId = requireNonBlank(parts[1], "txn_id");
                LocalDateTime ts = parseTimestamp(parts[2], "timestamp");
                String payerVpa = validateVpa(parts[3], "payer_vpa");
                String payeeVpa = validateVpa(parts[4], "payee_vpa");
                BigDecimal amount = parsePositiveAmount(parts[5], "amount");
                String remarks = parts.length > 6 ? parts[6].trim() : "";

                builder.txnId(txnId)
                        .timestamp(ts)
                        .payerVpa(payerVpa)
                        .payeeVpa(payeeVpa)
                        .amount(amount)
                        .remarks(remarks);
            }
            case BENE_RESPONSE -> {
                // BENE_RESPONSE|txn_id|timestamp|response
                if (parts.length != 4) {
                    throw new IllegalArgumentException(String.format("Expected 4 tokens for BENE_RESPONSE, found %d", parts.length));
                }
                String txnId = requireNonBlank(parts[1], "txn_id");
                LocalDateTime ts = parseTimestamp(parts[2], "timestamp");
                String responseCode = parts[3].trim().toUpperCase();
                if (!ALLOWED_BENE_RESPONSES.contains(responseCode)) {
                    throw new IllegalArgumentException(String.format("Invalid BENE_RESPONSE code: '%s'. Allowed: %s", responseCode, ALLOWED_BENE_RESPONSES));
                }

                builder.txnId(txnId)
                        .timestamp(ts)
                        .responseCode(responseCode);
            }
            case TIMEOUT_JOB -> {
                // TIMEOUT_JOB|timestamp
                if (parts.length != 2) {
                    throw new IllegalArgumentException(String.format("Expected 2 tokens for TIMEOUT_JOB, found %d", parts.length));
                }
                LocalDateTime ts = parseTimestamp(parts[1], "timestamp");
                builder.timestamp(ts);
            }
            case COLLECT -> {
                // COLLECT|txn_id|timestamp|requester_vpa|payer_vpa|amount|expiry_minutes|pin_flag
                if (parts.length != 8) {
                    throw new IllegalArgumentException(String.format("Expected 8 tokens for COLLECT, found %d", parts.length));
                }
                String txnId = requireNonBlank(parts[1], "txn_id");
                LocalDateTime ts = parseTimestamp(parts[2], "timestamp");
                String requesterVpa = validateVpa(parts[3], "requester_vpa");
                String payerVpa = validateVpa(parts[4], "payer_vpa");
                BigDecimal amount = parsePositiveAmount(parts[5], "amount");
                int expiryMinutes = parsePositiveInt(parts[6], "expiry_minutes");
                boolean pinFlag = parseBoolean(parts[7], "pin_flag");

                builder.txnId(txnId)
                        .timestamp(ts)
                        .payeeVpa(requesterVpa) // requester is payee
                        .payerVpa(payerVpa)
                        .amount(amount)
                        .expiryMinutes(expiryMinutes)
                        .pinFlag(pinFlag);
            }
            case COLLECT_APPROVE -> {
                // COLLECT_APPROVE|txn_id|timestamp
                if (parts.length != 3) {
                    throw new IllegalArgumentException(String.format("Expected 3 tokens for COLLECT_APPROVE, found %d", parts.length));
                }
                String txnId = requireNonBlank(parts[1], "txn_id");
                LocalDateTime ts = parseTimestamp(parts[2], "timestamp");
                builder.txnId(txnId).timestamp(ts);
            }
            case COLLECT_DECLINE -> {
                // COLLECT_DECLINE|txn_id|timestamp
                if (parts.length != 3) {
                    throw new IllegalArgumentException(String.format("Expected 3 tokens for COLLECT_DECLINE, found %d", parts.length));
                }
                String txnId = requireNonBlank(parts[1], "txn_id");
                LocalDateTime ts = parseTimestamp(parts[2], "timestamp");
                builder.txnId(txnId).timestamp(ts);
            }
            case REFUND -> {
                // REFUND|original_txn_id|timestamp|amount
                if (parts.length != 4) {
                    throw new IllegalArgumentException(String.format("Expected 4 tokens for REFUND, found %d", parts.length));
                }
                String origTxnId = requireNonBlank(parts[1], "original_txn_id");
                LocalDateTime ts = parseTimestamp(parts[2], "timestamp");
                BigDecimal amount = parsePositiveAmount(parts[3], "amount");
                builder.txnId(origTxnId).timestamp(ts).amount(amount);
            }
            case DISPUTE -> {
                // DISPUTE|dispute_id|timestamp|txn_id|type|raised_by|amount
                if (parts.length != 7) {
                    throw new IllegalArgumentException(String.format("Expected 7 tokens for DISPUTE, found %d", parts.length));
                }
                String disputeId = requireNonBlank(parts[1], "dispute_id");
                LocalDateTime ts = parseTimestamp(parts[2], "timestamp");
                String txnId = requireNonBlank(parts[3], "txn_id");
                String disputeType = parts[4].trim().toUpperCase();
                if (!ALLOWED_DISPUTE_TYPES.contains(disputeType)) {
                    throw new IllegalArgumentException(String.format("Invalid dispute type: '%s'. Allowed: %s", disputeType, ALLOWED_DISPUTE_TYPES));
                }
                String raisedBy = parts[5].trim().toUpperCase();
                if (!ALLOWED_RAISED_BY.contains(raisedBy)) {
                    throw new IllegalArgumentException(String.format("Invalid raised_by: '%s'. Allowed: %s", raisedBy, ALLOWED_RAISED_BY));
                }
                BigDecimal amount = parsePositiveAmount(parts[6], "amount");

                builder.disputeId(disputeId)
                        .timestamp(ts)
                        .txnId(txnId)
                        .disputeType(disputeType)
                        .raisedBy(raisedBy)
                        .amount(amount);
            }
            case DISPUTE_ACTION -> {
                // DISPUTE_ACTION|dispute_id|timestamp|action
                if (parts.length != 4) {
                    throw new IllegalArgumentException(String.format("Expected 4 tokens for DISPUTE_ACTION, found %d", parts.length));
                }
                String disputeId = requireNonBlank(parts[1], "dispute_id");
                LocalDateTime ts = parseTimestamp(parts[2], "timestamp");
                String action = parts[3].trim().toUpperCase();
                if (!ALLOWED_DISPUTE_ACTIONS.contains(action)) {
                    throw new IllegalArgumentException(String.format("Invalid dispute action: '%s'. Allowed: %s", action, ALLOWED_DISPUTE_ACTIONS));
                }

                builder.disputeId(disputeId)
                        .timestamp(ts)
                        .disputeAction(action);
            }
            case STATEMENT -> {
                // STATEMENT|vpa|start_date|end_date
                if (parts.length != 4) {
                    throw new IllegalArgumentException(String.format("Expected 4 tokens for STATEMENT, found %d", parts.length));
                }
                String vpa = validateVpa(parts[1], "vpa");
                LocalDate startDate = parseDate(parts[2], "start_date");
                LocalDate endDate = parseDate(parts[3], "end_date");
                if (startDate.isAfter(endDate)) {
                    throw new IllegalArgumentException(String.format("start_date '%s' cannot be after end_date '%s'", startDate, endDate));
                }

                builder.statementVpa(vpa)
                        .startDate(startDate)
                        .endDate(endDate);
            }
        }

        return builder.build();
    }

    private String requireNonBlank(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return value.trim();
    }

    private String validateVpa(String value, String fieldName) {
        String trimmed = requireNonBlank(value, fieldName);
        if (!trimmed.contains("@")) {
            throw new IllegalArgumentException(String.format("%s '%s' must contain '@'", fieldName, trimmed));
        }
        return trimmed;
    }

    private BigDecimal parsePositiveAmount(String value, String fieldName) {
        String trimmed = requireNonBlank(value, fieldName);
        try {
            BigDecimal amount = new BigDecimal(trimmed).setScale(2, RoundingMode.UNNECESSARY);
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(String.format("%s must be strictly positive (> 0.00), found '%s'", fieldName, trimmed));
            }
            return amount;
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException(String.format("Invalid monetary amount for %s: '%s'", fieldName, trimmed));
        }
    }

    private LocalDateTime parseTimestamp(String value, String fieldName) {
        String trimmed = requireNonBlank(value, fieldName);
        try {
            LocalDateTime ts = DateTimeUtils.parse(trimmed);
            if (ts == null) {
                throw new IllegalArgumentException("Failed to parse timestamp for " + fieldName);
            }
            return ts;
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(String.format("Invalid timestamp format for %s (expected YYYY-MM-DD HH:MM:SS): '%s'", fieldName, trimmed));
        }
    }

    private LocalDate parseDate(String value, String fieldName) {
        String trimmed = requireNonBlank(value, fieldName);
        try {
            return LocalDate.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(String.format("Invalid date format for %s (expected YYYY-MM-DD): '%s'", fieldName, trimmed));
        }
    }

    private int parsePositiveInt(String value, String fieldName) {
        String trimmed = requireNonBlank(value, fieldName);
        try {
            int num = Integer.parseInt(trimmed);
            if (num <= 0) {
                throw new IllegalArgumentException(String.format("%s must be positive, found %d", fieldName, num));
            }
            return num;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(String.format("Invalid integer for %s: '%s'", fieldName, trimmed));
        }
    }

    private boolean parseBoolean(String value, String fieldName) {
        String trimmed = requireNonBlank(value, fieldName).toLowerCase();
        if ("true".equals(trimmed)) return true;
        if ("false".equals(trimmed)) return false;
        throw new IllegalArgumentException(String.format("Invalid boolean for %s (must be 'true' or 'false'): '%s'", fieldName, value));
    }
}
