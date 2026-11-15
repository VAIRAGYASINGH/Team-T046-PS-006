package com.ps006.upiswitch.command.loader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class MasterDataInitializer {

    private static final Logger log = LoggerFactory.getLogger(MasterDataInitializer.class);

    private final HolidayLoader holidayLoader;
    private final UserLoader userLoader;
    private final MerchantLoader merchantLoader;

    public MasterDataInitializer(HolidayLoader holidayLoader,
                                 UserLoader userLoader,
                                 MerchantLoader merchantLoader) {
        this.holidayLoader = holidayLoader;
        this.userLoader = userLoader;
        this.merchantLoader = merchantLoader;
    }

    @Transactional
    public MasterDataReport initialize(Path inputDir) throws IOException {
        log.info("Initializing master data from directory: {}", inputDir.toAbsolutePath());
        MasterDataReport report = new MasterDataReport();

        if (!Files.exists(inputDir)) {
            report.addFatalError("Input directory does not exist: " + inputDir);
            return report;
        }

        Path holidaysFile = inputDir.resolve("holidays.csv");
        Path usersFile = inputDir.resolve("users.csv");
        Path merchantsFile = inputDir.resolve("merchants.csv");

        // 1. Load Holidays
        IngestionResult holidayResult = holidayLoader.load(holidaysFile);
        report.addResult(holidayResult);

        // 2. Load Users
        IngestionResult userResult = userLoader.load(usersFile);
        report.addResult(userResult);

        // 3. Load Merchants
        IngestionResult merchantResult = merchantLoader.load(merchantsFile);
        report.addResult(merchantResult);

        log.info("Master data initialization summary: total loaded = {}, total errors = {}",
                report.getTotalLoaded(), report.getTotalErrors());

        return report;
    }

    public static class MasterDataReport {
        private final List<IngestionResult> results = new ArrayList<>();
        private final List<String> fatalErrors = new ArrayList<>();

        public void addResult(IngestionResult result) {
            this.results.add(result);
        }

        public void addFatalError(String error) {
            this.fatalErrors.add(error);
        }

        public int getTotalLoaded() {
            return results.stream().mapToInt(IngestionResult::getSuccessfullyLoaded).sum();
        }

        public int getTotalErrors() {
            return results.stream().mapToInt(IngestionResult::getErrorCount).sum() + fatalErrors.size();
        }

        public boolean hasErrors() {
            return getTotalErrors() > 0;
        }

        public List<IngestionResult> getResults() {
            return Collections.unmodifiableList(results);
        }

        public List<String> getFatalErrors() {
            return Collections.unmodifiableList(fatalErrors);
        }
    }
}

