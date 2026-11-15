package com.ps006.upiswitch.command.runner;

import com.ps006.upiswitch.command.dispatcher.CommandDispatcher;
import com.ps006.upiswitch.command.loader.MasterDataInitializer;
import com.ps006.upiswitch.command.model.ParsedCommand;
import com.ps006.upiswitch.command.parser.CommandParser;
import com.ps006.upiswitch.config.SwitchProperties;
import com.ps006.upiswitch.reporting.service.ReportGeneratorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

@Component
public class SwitchApplicationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SwitchApplicationRunner.class);

    private final SwitchProperties switchProperties;
    private final MasterDataInitializer masterDataInitializer;
    private final CommandParser commandParser;
    private final CommandDispatcher commandDispatcher;
    private final ReportGeneratorService reportGeneratorService;

    public SwitchApplicationRunner(SwitchProperties switchProperties,
                                  MasterDataInitializer masterDataInitializer,
                                  CommandParser commandParser,
                                  CommandDispatcher commandDispatcher,
                                  ReportGeneratorService reportGeneratorService) {
        this.switchProperties = switchProperties;
        this.masterDataInitializer = masterDataInitializer;
        this.commandParser = commandParser;
        this.commandDispatcher = commandDispatcher;
        this.reportGeneratorService = reportGeneratorService;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Resolve input and output directories from CLI args or properties
        String inputDirStr = args.containsOption("input-dir")
                ? args.getOptionValues("input-dir").get(0)
                : switchProperties.getInputDir();

        String outputDirStr = args.containsOption("output-dir")
                ? args.getOptionValues("output-dir").get(0)
                : switchProperties.getOutputDir();

        Path inputDir = Paths.get(inputDirStr);
        Path outputDir = Paths.get(outputDirStr);

        log.info("Starting PS-006 UPI Payment Switch");
        log.info("Input directory: {}", inputDir.toAbsolutePath());
        log.info("Output directory: {}", outputDir.toAbsolutePath());

        if (!Files.exists(inputDir)) {
            log.warn("Input directory '{}' does not exist. Skipping file-driven execution.", inputDir);
            return;
        }

        // Step 1: Load master data (users, merchants, holidays)
        log.info("Phase 1: Loading master data...");
        MasterDataInitializer.MasterDataReport report = masterDataInitializer.initialize(inputDir);
        if (report.hasErrors()) {
            log.warn("Master data initialized with {} warnings/errors", report.getTotalErrors());
        }

        // Step 2: Process commands sequentially
        Path commandsPath = inputDir.resolve("commands.txt");
        if (!Files.exists(commandsPath)) {
            log.warn("commands.txt not found in input directory: {}", inputDir);
            return;
        }

        log.info("Phase 2: Processing commands from {} in sequential file order...", commandsPath);
        int lineNo = 0;
        try (BufferedReader reader = Files.newBufferedReader(commandsPath, StandardCharsets.UTF_8)) {
            String rawLine;
            while ((rawLine = reader.readLine()) != null) {
                lineNo++;
                Optional<ParsedCommand> cmdOpt = commandParser.parseLine(lineNo, rawLine);
                if (cmdOpt.isPresent()) {
                    commandDispatcher.dispatch(cmdOpt.get());
                }
            }
        }
        log.info("Processed {} lines from commands.txt", lineNo);

        // Step 3: Generate output CSV reports
        log.info("Phase 3: Generating final reconciliation and audit reports in {}...", outputDir);
        Files.createDirectories(outputDir);

        reportGeneratorService.generateTxnLogCsv(commandDispatcher.getTxnLogRows(), outputDir.resolve("txn_log.csv"));
        reportGeneratorService.generateTxnFinalCsv(commandDispatcher.getTxnFinalRows(), outputDir.resolve("txn_final.csv"));
        reportGeneratorService.generateDisputeLogCsv(commandDispatcher.getDisputeLogRows(), outputDir.resolve("dispute_log.csv"));
        reportGeneratorService.generateStatementsCsv(commandDispatcher.getStatementRows(), outputDir.resolve("statements.csv"));

        log.info("PS-006 UPI Payment Switch processing complete!");
    }
}
