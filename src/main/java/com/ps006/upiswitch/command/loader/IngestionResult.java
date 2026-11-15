package com.ps006.upiswitch.command.loader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Encapsulates the results, metrics, and validation errors of a master data ingestion run.
 */
public class IngestionResult {

    private final String fileName;
    private int totalRowsProcessed = 0;
    private int successfullyLoaded = 0;
    private final List<RowError> errors = new ArrayList<>();

    public IngestionResult(String fileName) {
        this.fileName = fileName;
    }

    public void incrementProcessed() {
        this.totalRowsProcessed++;
    }

    public void incrementSuccess() {
        this.successfullyLoaded++;
    }

    public void addError(int lineNo, String rawRow, String errorMessage) {
        this.errors.add(new RowError(lineNo, rawRow, errorMessage));
    }

    public String getFileName() { return fileName; }
    public int getTotalRowsProcessed() { return totalRowsProcessed; }
    public int getSuccessfullyLoaded() { return successfullyLoaded; }
    public int getErrorCount() { return errors.size(); }
    public boolean hasErrors() { return !errors.isEmpty(); }
    public List<RowError> getErrors() { return Collections.unmodifiableList(errors); }

    public record RowError(int lineNo, String rawRow, String errorMessage) {
        @Override
        public String toString() {
            return String.format("Line %d: %s [Row: '%s']", lineNo, errorMessage, rawRow);
        }
    }
}

