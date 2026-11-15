package com.ps006.upiswitch.statement.service;

import com.ps006.upiswitch.ledger.entity.LedgerEntryEntity;
import com.ps006.upiswitch.ledger.service.LedgerService;
import com.ps006.upiswitch.statement.dto.StatementRecordDto;
import com.ps006.upiswitch.vpa.model.VpaDetails;
import com.ps006.upiswitch.vpa.service.VpaService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class StatementService {

    private final LedgerService ledgerService;
    private final VpaService vpaService;
    private final List<StatementRecordDto> accumulatedStatements = new ArrayList<>();

    public StatementService(LedgerService ledgerService, VpaService vpaService) {
        this.ledgerService = ledgerService;
        this.vpaService = vpaService;
    }

    public List<StatementRecordDto> generateStatementForVpa(String vpa, LocalDate startDate, LocalDate endDate) {
        VpaDetails vpaDetails = vpaService.getVpaOrThrow(vpa);
        List<LedgerEntryEntity> entries = ledgerService.getEntriesForAccount(vpaDetails.accountId());

        List<StatementRecordDto> dtos = entries.stream()
                .filter(e -> {
                    LocalDate entryDate = e.getEntryTimestamp().toLocalDate();
                    boolean afterOrOnStart = (startDate == null || !entryDate.isBefore(startDate));
                    boolean beforeOrOnEnd = (endDate == null || !entryDate.isAfter(endDate));
                    return afterOrOnStart && beforeOrOnEnd;
                })
                .map(e -> new StatementRecordDto(
                        vpa,
                        e.getEntryTimestamp(),
                        e.getTxnId(),
                        e.getLegType(),
                        e.getDebitAmount(),
                        e.getCreditAmount(),
                        e.getBalanceAfter()
                ))
                .collect(Collectors.toList());

        accumulatedStatements.addAll(dtos);
        return dtos;
    }

    public List<StatementRecordDto> generateStatementForVpa(String vpa) {
        return generateStatementForVpa(vpa, null, null);
    }

    public List<StatementRecordDto> getAllAccumulatedStatements() {
        return Collections.unmodifiableList(accumulatedStatements);
    }

    public void clearAccumulatedStatements() {
        accumulatedStatements.clear();
    }
}
