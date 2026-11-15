package com.ps006.upiswitch.reporting.service;

import com.ps006.upiswitch.common.util.DateTimeUtils;
import com.ps006.upiswitch.reporting.model.DisputeLogRow;
import com.ps006.upiswitch.reporting.model.StatementRow;
import com.ps006.upiswitch.reporting.model.TxnFinalRow;
import com.ps006.upiswitch.reporting.model.TxnLogRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class ReportGeneratorService {

    private static final Logger log = LoggerFactory.getLogger(ReportGeneratorService.class);

    public void generateTxnLogCsv(List<TxnLogRow> rows, Path outputPath) throws IOException {
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write("line_no,txn_id,command,timestamp,payer_vpa,payee_vpa,amount,status,rrn,rule_ids\n");
            for (TxnLogRow r : rows) {
                writer.write(String.format("%d,%s,%s,%s,%s,%s,%s,%s,%s,%s\n",
                        r.lineNo(),
                        nullSafe(r.txnId()),
                        nullSafe(r.command()),
                        formatTime(r.timestamp()),
                        nullSafe(r.payerVpa()),
                        nullSafe(r.payeeVpa()),
                        formatAmount(r.amount()),
                        nullSafe(r.status()),
                        nullSafe(r.rrn()),
                        nullSafe(r.ruleIds())
                ));
            }
        }
        log.info("Wrote {} rows to {}", rows.size(), outputPath);
    }

    public void generateTxnFinalCsv(List<TxnFinalRow> rows, Path outputPath) throws IOException {
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write("txn_id,final_status,amount,refunded_amount,reversed_at,deemed_success_at\n");
            for (TxnFinalRow r : rows) {
                writer.write(String.format("%s,%s,%s,%s,%s,%s\n",
                        nullSafe(r.txnId()),
                        nullSafe(r.finalStatus()),
                        formatAmount(r.amount()),
                        formatAmount(r.refundedAmount() != null ? r.refundedAmount() : BigDecimal.ZERO),
                        formatTime(r.reversedAt()),
                        formatTime(r.deemedSuccessAt())
                ));
            }
        }
        log.info("Wrote {} rows to {}", rows.size(), outputPath);
    }

    public void generateDisputeLogCsv(List<DisputeLogRow> rows, Path outputPath) throws IOException {
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write("line_no,dispute_id,txn_id,type,action,timestamp,result_state,deadline,rule_ids\n");
            for (DisputeLogRow r : rows) {
                writer.write(String.format("%d,%s,%s,%s,%s,%s,%s,%s,%s\n",
                        r.lineNo(),
                        nullSafe(r.disputeId()),
                        nullSafe(r.txnId()),
                        nullSafe(r.type()),
                        nullSafe(r.action()),
                        formatTime(r.timestamp()),
                        nullSafe(r.resultState()),
                        formatTime(r.deadline()),
                        nullSafe(r.ruleIds())
                ));
            }
        }
        log.info("Wrote {} rows to {}", rows.size(), outputPath);
    }

    public void generateStatementsCsv(List<StatementRow> rows, Path outputPath) throws IOException {
        if (outputPath.getParent() != null) {
            Files.createDirectories(outputPath.getParent());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8)) {
            writer.write("vpa,timestamp,txn_id,leg_type,debit,credit,running_balance\n");
            for (StatementRow r : rows) {
                writer.write(String.format("%s,%s,%s,%s,%s,%s,%s\n",
                        nullSafe(r.vpa()),
                        formatTime(r.timestamp()),
                        nullSafe(r.txnId()),
                        nullSafe(r.legType()),
                        formatAmount(r.debit()),
                        formatAmount(r.credit()),
                        formatAmount(r.runningBalance())
                ));
            }
        }
        log.info("Wrote {} rows to {}", rows.size(), outputPath);
    }

    private String formatTime(LocalDateTime dt) {
        return dt != null ? DateTimeUtils.format(dt) : "";
    }

    private String formatAmount(BigDecimal amount) {
        return amount != null ? amount.setScale(2, RoundingMode.HALF_UP).toPlainString() : "";
    }

    private String nullSafe(String val) {
        return val != null ? val : "";
    }
}

