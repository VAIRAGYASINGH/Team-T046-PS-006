package com.ps006.upiswitch.command.model;

import com.ps006.upiswitch.collect.service.CollectService;
import com.ps006.upiswitch.common.repository.ReconciliationRecordRepository;
import com.ps006.upiswitch.dispute.service.DisputeService;
import com.ps006.upiswitch.payment.repository.TransactionRepository;
import com.ps006.upiswitch.payment.service.PaymentService;
import com.ps006.upiswitch.reporting.model.DisputeLogRow;
import com.ps006.upiswitch.reporting.model.StatementRow;
import com.ps006.upiswitch.reporting.model.TxnLogRow;
import com.ps006.upiswitch.reversal.service.RefundService;
import com.ps006.upiswitch.reversal.service.TimeoutJobService;
import com.ps006.upiswitch.statement.service.StatementService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Execution context carrying transactional services, repositories, and output collectors
 * for sequential command dispatching.
 */
public class CommandExecutionContext {

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

    public CommandExecutionContext(PaymentService paymentService,
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

    public PaymentService getPaymentService() { return paymentService; }
    public TimeoutJobService getTimeoutJobService() { return timeoutJobService; }
    public CollectService getCollectService() { return collectService; }
    public RefundService getRefundService() { return refundService; }
    public DisputeService getDisputeService() { return disputeService; }
    public StatementService getStatementService() { return statementService; }
    public TransactionRepository getTransactionRepository() { return transactionRepository; }
    public ReconciliationRecordRepository getReconciliationRecordRepository() { return reconciliationRecordRepository; }

    public void addTxnLogRow(TxnLogRow row) { if (row != null) txnLogRows.add(row); }
    public void addDisputeLogRow(DisputeLogRow row) { if (row != null) disputeLogRows.add(row); }
    public void addStatementRow(StatementRow row) { if (row != null) statementRows.add(row); }

    public List<TxnLogRow> getTxnLogRows() { return Collections.unmodifiableList(txnLogRows); }
    public List<DisputeLogRow> getDisputeLogRows() { return Collections.unmodifiableList(disputeLogRows); }
    public List<StatementRow> getStatementRows() { return Collections.unmodifiableList(statementRows); }
}

