package com.ps006.upiswitch.command.dispatcher;

import com.ps006.upiswitch.collect.service.CollectService;
import com.ps006.upiswitch.command.model.Command;
import com.ps006.upiswitch.command.model.CommandExecutionContext;
import com.ps006.upiswitch.command.model.ParsedCommand;
import com.ps006.upiswitch.common.repository.ReconciliationRecordRepository;
import com.ps006.upiswitch.dispute.service.DisputeService;
import com.ps006.upiswitch.payment.entity.TransactionEntity;
import com.ps006.upiswitch.payment.repository.TransactionRepository;
import com.ps006.upiswitch.payment.service.PaymentService;
import com.ps006.upiswitch.reporting.model.DisputeLogRow;
import com.ps006.upiswitch.reporting.model.StatementRow;
import com.ps006.upiswitch.reporting.model.TxnFinalRow;
import com.ps006.upiswitch.reporting.model.TxnLogRow;
import com.ps006.upiswitch.reversal.service.RefundService;
import com.ps006.upiswitch.reversal.service.TimeoutJobService;
import com.ps006.upiswitch.statement.dto.StatementRecordDto;
import com.ps006.upiswitch.statement.service.StatementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class CommandDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CommandDispatcher.class);

    private final PaymentService paymentService;
    private final TimeoutJobService timeoutJobService;
    private final CollectService collectService;
    private final RefundService refundService;
    private final DisputeService disputeService;
    private final StatementService statementService;
    private final TransactionRepository transactionRepository;
    private final ReconciliationRecordRepository reconciliationRecordRepository;

    private final List<TxnLogRow> txnLogRows = new ArrayList<>();
    private final List<DisputeLogRow> disputeLogRows = new ArrayList<>();
    private final List<StatementRow> statementRows = new ArrayList<>();

    public CommandDispatcher(PaymentService paymentService,
                             TimeoutJobService timeoutJobService,
                             CollectService collectService,
                             RefundService refundService,
                             DisputeService disputeService,
                             StatementService statementService,
                             TransactionRepository transactionRepository,
                             ReconciliationRecordRepository reconciliationRecordRepository) {
        this.paymentService = paymentService;
        this.timeoutJobService = timeoutJobService;
        this.collectService = collectService;
        this.refundService = refundService;
        this.disputeService = disputeService;
        this.statementService = statementService;
        this.transactionRepository = transactionRepository;
        this.reconciliationRecordRepository = reconciliationRecordRepository;
    }

    public void dispatch(Command command, CommandExecutionContext context) {
        if (command == null || context == null) return;
        log.debug("Dispatching command at line {}: {}", command.getLineNo(), command.getType());
        command.execute(context);
    }

    public void dispatch(ParsedCommand cmd) {
        if (cmd == null || !cmd.valid()) return;
        log.debug("Dispatching line {}: {}", cmd.lineNo(), cmd.commandType());
        switch (cmd.commandType()) {
            case PAY -> {
                TxnLogRow row = paymentService.processPayment(
                        cmd.lineNo(), cmd.txnId(), cmd.timestamp(),
                        cmd.payerVpa(), cmd.payeeVpa(), cmd.amount(), cmd.remarks()
                );
                txnLogRows.add(row);
            }
            case BENE_RESPONSE -> {
                paymentService.handleBeneficiaryResponse(
                        cmd.txnId(), cmd.timestamp(), cmd.responseCode()
                );
            }
            case TIMEOUT_JOB -> {
                timeoutJobService.processTimeouts(cmd.timestamp());
            }
            case COLLECT -> {
                TxnLogRow row = collectService.initiateCollect(
                        cmd.lineNo(), cmd.txnId(), cmd.timestamp(),
                        cmd.payeeVpa(), cmd.payerVpa(), cmd.amount(),
                        cmd.expiryMinutes(), cmd.pinFlag()
                );
                txnLogRows.add(row);
            }
            case COLLECT_APPROVE -> {
                TxnLogRow row = collectService.approveCollect(
                        cmd.lineNo(), cmd.txnId(), cmd.timestamp()
                );
                txnLogRows.add(row);
            }
            case COLLECT_DECLINE -> {
                TxnLogRow row = collectService.declineCollect(
                        cmd.lineNo(), cmd.txnId(), cmd.timestamp()
                );
                txnLogRows.add(row);
            }
            case REFUND -> {
                TxnLogRow row = refundService.processRefund(
                        cmd.lineNo(), cmd.txnId(), cmd.timestamp(), cmd.amount()
                );
                txnLogRows.add(row);
            }
            case DISPUTE -> {
                DisputeLogRow row = disputeService.raiseDispute(
                        cmd.lineNo(), cmd.disputeId(), cmd.timestamp(),
                        cmd.txnId(), cmd.disputeType(), cmd.raisedBy(), cmd.amount()
                );
                disputeLogRows.add(row);
            }
            case DISPUTE_ACTION -> {
                DisputeLogRow row = disputeService.handleDisputeAction(
                        cmd.lineNo(), cmd.disputeId(), cmd.timestamp(), cmd.disputeAction()
                );
                disputeLogRows.add(row);
            }
            case STATEMENT -> {
                List<StatementRecordDto> dtos = statementService.generateStatementForVpa(
                        cmd.statementVpa(), cmd.startDate(), cmd.endDate()
                );
                for (StatementRecordDto dto : dtos) {
                    statementRows.add(new StatementRow(
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

    public CommandExecutionContext createExecutionContext() {
        return new CommandExecutionContext(
                paymentService,
                timeoutJobService,
                collectService,
                refundService,
                disputeService,
                statementService,
                transactionRepository,
                reconciliationRecordRepository
        );
    }

    public void reset() {
        txnLogRows.clear();
        disputeLogRows.clear();
        statementRows.clear();
        statementService.clearAccumulatedStatements();
    }

    public List<TxnLogRow> getTxnLogRows() {
        return txnLogRows;
    }

    public List<DisputeLogRow> getDisputeLogRows() {
        return disputeLogRows;
    }

    public List<StatementRow> getStatementRows() {
        return statementRows;
    }

    public List<TxnFinalRow> getTxnFinalRows() {
        return transactionRepository.findAll().stream()
                .sorted(Comparator.comparing(TransactionEntity::getTxnId))
                .map(t -> new TxnFinalRow(
                        t.getTxnId(),
                        t.getStatus().name(),
                        t.getAmount(),
                        t.getRefundedAmount(),
                        t.getReversedAt(),
                        t.getDeemedSuccessAt()
                ))
                .collect(Collectors.toList());
    }
}
