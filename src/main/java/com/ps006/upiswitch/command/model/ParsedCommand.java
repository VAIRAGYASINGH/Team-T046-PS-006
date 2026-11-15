package com.ps006.upiswitch.command.model;

import com.ps006.upiswitch.reporting.model.DisputeLogRow;
import com.ps006.upiswitch.reporting.model.StatementRow;
import com.ps006.upiswitch.reporting.model.TxnLogRow;
import com.ps006.upiswitch.statement.dto.StatementRecordDto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record ParsedCommand(
        int lineNo,
        String rawLine,
        CommandType commandType,
        String txnId,
        LocalDateTime timestamp,
        String payerVpa,
        String payeeVpa,
        BigDecimal amount,
        String remarks,
        String responseCode,
        Integer expiryMinutes,
        Boolean pinFlag,
        String disputeId,
        String disputeType,
        String raisedBy,
        String disputeAction,
        String statementVpa,
        LocalDate startDate,
        LocalDate endDate,
        boolean valid,
        String errorMessage
) implements Command {

    @Override
    public int getLineNo() { return lineNo; }

    @Override
    public String getRawText() { return rawLine; }

    @Override
    public CommandType getType() { return commandType; }

    @Override
    public LocalDateTime getTimestamp() { return timestamp; }

    @Override
    public ParsedCommand getParsedCommand() { return this; }

    @Override
    public void execute(CommandExecutionContext context) {
        if (!valid) {
            return;
        }

        switch (commandType) {
            case PAY -> {
                TxnLogRow row = context.getPaymentService().processPayment(
                        lineNo, txnId, timestamp, payerVpa, payeeVpa, amount, remarks
                );
                context.addTxnLogRow(row);
            }
            case BENE_RESPONSE -> {
                context.getPaymentService().handleBeneficiaryResponse(txnId, timestamp, responseCode);
            }
            case TIMEOUT_JOB -> {
                context.getTimeoutJobService().processTimeouts(timestamp);
            }
            case COLLECT -> {
                TxnLogRow row = context.getCollectService().initiateCollect(
                        lineNo, txnId, timestamp, payeeVpa, payerVpa, amount, expiryMinutes, pinFlag
                );
                context.addTxnLogRow(row);
            }
            case COLLECT_APPROVE -> {
                TxnLogRow row = context.getCollectService().approveCollect(lineNo, txnId, timestamp);
                context.addTxnLogRow(row);
            }
            case COLLECT_DECLINE -> {
                TxnLogRow row = context.getCollectService().declineCollect(lineNo, txnId, timestamp);
                context.addTxnLogRow(row);
            }
            case REFUND -> {
                TxnLogRow row = context.getRefundService().processRefund(lineNo, txnId, timestamp, amount);
                context.addTxnLogRow(row);
            }
            case DISPUTE -> {
                DisputeLogRow row = context.getDisputeService().raiseDispute(
                        lineNo, disputeId, timestamp, txnId, disputeType, raisedBy, amount
                );
                context.addDisputeLogRow(row);
            }
            case DISPUTE_ACTION -> {
                DisputeLogRow row = context.getDisputeService().handleDisputeAction(
                        lineNo, disputeId, timestamp, disputeAction
                );
                context.addDisputeLogRow(row);
            }
            case STATEMENT -> {
                List<StatementRecordDto> dtos = context.getStatementService().generateStatementForVpa(
                        statementVpa, startDate, endDate
                );
                for (StatementRecordDto dto : dtos) {
                    context.addStatementRow(new StatementRow(
                            dto.vpa(),
                            dto.timestamp(),
                            dto.txnId(),
                            dto.legType().name(),
                            dto.debit(),
                            dto.credit(),
                            dto.runningBalance()
                    ));
                }
            }
        }
    }

    public static Builder builder(int lineNo, String rawLine, CommandType commandType) {
        return new Builder(lineNo, rawLine, commandType);
    }

    public static ParsedCommand invalid(int lineNo, String rawLine, CommandType commandType, String errorMessage) {
        return new ParsedCommand(
                lineNo, rawLine, commandType, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, false, errorMessage
        );
    }

    public static class Builder {
        private final int lineNo;
        private final String rawLine;
        private final CommandType commandType;
        private String txnId;
        private LocalDateTime timestamp;
        private String payerVpa;
        private String payeeVpa;
        private BigDecimal amount;
        private String remarks;
        private String responseCode;
        private Integer expiryMinutes;
        private Boolean pinFlag;
        private String disputeId;
        private String disputeType;
        private String raisedBy;
        private String disputeAction;
        private String statementVpa;
        private LocalDate startDate;
        private LocalDate endDate;
        private boolean valid = true;
        private String errorMessage;

        public Builder(int lineNo, String rawLine, CommandType commandType) {
            this.lineNo = lineNo;
            this.rawLine = rawLine;
            this.commandType = commandType;
        }

        public Builder txnId(String txnId) { this.txnId = txnId; return this; }
        public Builder timestamp(LocalDateTime timestamp) { this.timestamp = timestamp; return this; }
        public Builder payerVpa(String payerVpa) { this.payerVpa = payerVpa; return this; }
        public Builder payeeVpa(String payeeVpa) { this.payeeVpa = payeeVpa; return this; }
        public Builder amount(BigDecimal amount) { this.amount = amount; return this; }
        public Builder remarks(String remarks) { this.remarks = remarks; return this; }
        public Builder responseCode(String responseCode) { this.responseCode = responseCode; return this; }
        public Builder expiryMinutes(Integer expiryMinutes) { this.expiryMinutes = expiryMinutes; return this; }
        public Builder pinFlag(Boolean pinFlag) { this.pinFlag = pinFlag; return this; }
        public Builder disputeId(String disputeId) { this.disputeId = disputeId; return this; }
        public Builder disputeType(String disputeType) { this.disputeType = disputeType; return this; }
        public Builder raisedBy(String raisedBy) { this.raisedBy = raisedBy; return this; }
        public Builder disputeAction(String disputeAction) { this.disputeAction = disputeAction; return this; }
        public Builder statementVpa(String statementVpa) { this.statementVpa = statementVpa; return this; }
        public Builder startDate(LocalDate startDate) { this.startDate = startDate; return this; }
        public Builder endDate(LocalDate endDate) { this.endDate = endDate; return this; }
        public Builder valid(boolean valid) { this.valid = valid; return this; }
        public Builder errorMessage(String errorMessage) { this.errorMessage = errorMessage; return this; }

        public ParsedCommand build() {
            return new ParsedCommand(
                    lineNo, rawLine, commandType, txnId, timestamp, payerVpa, payeeVpa, amount,
                    remarks, responseCode, expiryMinutes, pinFlag, disputeId, disputeType,
                    raisedBy, disputeAction, statementVpa, startDate, endDate, valid, errorMessage
            );
        }
    }
}
