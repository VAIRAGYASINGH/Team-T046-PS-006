package com.ps006.upiswitch.command.loader;

import com.ps006.upiswitch.account.entity.AccountEntity;
import com.ps006.upiswitch.account.service.AccountService;
import com.ps006.upiswitch.common.util.DateTimeUtils;
import com.ps006.upiswitch.counter.entity.DailyCounterEntity;
import com.ps006.upiswitch.counter.entity.MonthlyCounterEntity;
import com.ps006.upiswitch.counter.repository.DailyCounterRepository;
import com.ps006.upiswitch.counter.repository.MonthlyCounterRepository;
import com.ps006.upiswitch.user.entity.UserEntity;
import com.ps006.upiswitch.user.service.UserService;
import com.ps006.upiswitch.vpa.entity.VpaEntity;
import com.ps006.upiswitch.vpa.model.OwnerType;
import com.ps006.upiswitch.vpa.repository.VpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

@Component
public class UserLoader {

    private static final Logger log = LoggerFactory.getLogger(UserLoader.class);
    private static final String EXPECTED_HEADER =
            "user_id,name,vpa,bank_account,ifsc,balance,vpa_created_at,device_bound_at,mobile,counter_date,daily_debit_count,daily_debit_amount,monthly_debit_amount";

    private final UserService userService;
    private final AccountService accountService;
    private final VpaRepository vpaRepository;
    private final DailyCounterRepository dailyCounterRepository;
    private final MonthlyCounterRepository monthlyCounterRepository;

    public UserLoader(UserService userService,
                      AccountService accountService,
                      VpaRepository vpaRepository,
                      DailyCounterRepository dailyCounterRepository,
                      MonthlyCounterRepository monthlyCounterRepository) {
        this.userService = userService;
        this.accountService = accountService;
        this.vpaRepository = vpaRepository;
        this.dailyCounterRepository = dailyCounterRepository;
        this.monthlyCounterRepository = monthlyCounterRepository;
    }

    @Transactional
    public IngestionResult load(Path usersPath) throws IOException {
        IngestionResult result = new IngestionResult("users.csv");
        if (!Files.exists(usersPath)) {
            result.addError(0, "", "Users file not found at " + usersPath);
            return result;
        }

        Set<String> seenUserIds = new HashSet<>();
        Set<String> seenVpas = new HashSet<>();
        Set<String> seenBankAccounts = new HashSet<>();

        try (BufferedReader reader = Files.newBufferedReader(usersPath, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            int lineNo = 1;

            if (headerLine == null) {
                result.addError(1, "", "File is completely empty");
                return result;
            }

            if (!headerLine.trim().equalsIgnoreCase(EXPECTED_HEADER)) {
                result.addError(1, headerLine, String.format("Invalid header. Expected '%s', found '%s'", EXPECTED_HEADER, headerLine));
                return result;
            }

            String row;
            while ((row = reader.readLine()) != null) {
                lineNo++;
                String trimmed = row.trim();
                if (trimmed.isEmpty()) continue;

                result.incrementProcessed();
                String[] parts = trimmed.split(",", -1);
                if (parts.length != 13) {
                    result.addError(lineNo, trimmed, String.format("Expected 13 columns, found %d", parts.length));
                    continue;
                }

                String userId = parts[0].trim();
                String name = parts[1].trim();
                String vpa = parts[2].trim();
                String bankAccount = parts[3].trim();
                String ifsc = parts[4].trim();
                String balanceStr = parts[5].trim();
                String vpaCreatedAtStr = parts[6].trim();
                String deviceBoundAtStr = parts[7].trim();
                String mobile = parts[8].trim();
                String counterDateStr = parts[9].trim();
                String dailyDebitCountStr = parts[10].trim();
                String dailyDebitAmountStr = parts[11].trim();
                String monthlyDebitAmountStr = parts[12].trim();

                // Validate required fields
                if (userId.isEmpty() || name.isEmpty() || vpa.isEmpty() || bankAccount.isEmpty() || ifsc.isEmpty()) {
                    result.addError(lineNo, trimmed, "Required identifier/account fields cannot be blank");
                    continue;
                }

                if (!vpa.contains("@")) {
                    result.addError(lineNo, trimmed, "Invalid VPA format (must contain '@'): " + vpa);
                    continue;
                }

                // Duplicate validation
                if (seenUserIds.contains(userId)) {
                    result.addError(lineNo, trimmed, "Duplicate user_id detected: " + userId);
                    continue;
                }
                if (seenVpas.contains(vpa)) {
                    result.addError(lineNo, trimmed, "Duplicate VPA detected: " + vpa);
                    continue;
                }
                if (seenBankAccounts.contains(bankAccount)) {
                    result.addError(lineNo, trimmed, "Duplicate bank_account detected: " + bankAccount);
                    continue;
                }

                // Monetary values validation
                BigDecimal balance;
                BigDecimal dailyDebitAmount;
                BigDecimal monthlyDebitAmount;
                try {
                    balance = new BigDecimal(balanceStr).setScale(2, RoundingMode.UNNECESSARY);
                    dailyDebitAmount = new BigDecimal(dailyDebitAmountStr).setScale(2, RoundingMode.UNNECESSARY);
                    monthlyDebitAmount = new BigDecimal(monthlyDebitAmountStr).setScale(2, RoundingMode.UNNECESSARY);

                    if (balance.compareTo(BigDecimal.ZERO) < 0 ||
                        dailyDebitAmount.compareTo(BigDecimal.ZERO) < 0 ||
                        monthlyDebitAmount.compareTo(BigDecimal.ZERO) < 0) {
                        result.addError(lineNo, trimmed, "Monetary amounts cannot be negative");
                        continue;
                    }
                } catch (NumberFormatException | ArithmeticException e) {
                    result.addError(lineNo, trimmed, "Invalid monetary format: " + e.getMessage());
                    continue;
                }

                // Timestamp validations
                LocalDateTime vpaCreatedAt;
                LocalDateTime deviceBoundAt = null;
                try {
                    vpaCreatedAt = DateTimeUtils.parse(vpaCreatedAtStr);
                    if (vpaCreatedAt == null) {
                        result.addError(lineNo, trimmed, "vpa_created_at cannot be blank");
                        continue;
                    }
                    if (!deviceBoundAtStr.isEmpty()) {
                        deviceBoundAt = DateTimeUtils.parse(deviceBoundAtStr);
                    }
                } catch (DateTimeParseException e) {
                    result.addError(lineNo, trimmed, "Invalid timestamp format (expected YYYY-MM-DD HH:MM:SS): " + e.getMessage());
                    continue;
                }

                // Counter fields validation
                LocalDate counterDate;
                int dailyDebitCount;
                try {
                    counterDate = counterDateStr.isEmpty() ? LocalDate.of(2026, 10, 1) : LocalDate.parse(counterDateStr);
                    dailyDebitCount = Integer.parseInt(dailyDebitCountStr);
                    if (dailyDebitCount < 0) {
                        result.addError(lineNo, trimmed, "daily_debit_count cannot be negative: " + dailyDebitCount);
                        continue;
                    }
                } catch (DateTimeParseException | NumberFormatException e) {
                    result.addError(lineNo, trimmed, "Invalid counter date or debit count: " + e.getMessage());
                    continue;
                }

                seenUserIds.add(userId);
                seenVpas.add(vpa);
                seenBankAccounts.add(bankAccount);

                // Persist User Entity
                UserEntity user = new UserEntity(userId, name, mobile, deviceBoundAt, vpaCreatedAt);
                userService.saveEntity(user);

                // Persist Account Entity (linked to user)
                AccountEntity account = new AccountEntity(
                        null, bankAccount, ifsc, userId, null, balance, "INR", 0L, vpaCreatedAt, LocalDateTime.now()
                );
                account = accountService.saveEntity(account);

                // Persist VPA Entity
                VpaEntity vpaEntity = new VpaEntity(vpa, account.getAccountId(), OwnerType.USER, userId, vpaCreatedAt, true);
                vpaRepository.save(vpaEntity);

                // Seed Daily Counter
                DailyCounterEntity dailyCounter = new DailyCounterEntity(userId, counterDate, dailyDebitCount, dailyDebitAmount);
                dailyCounterRepository.save(dailyCounter);

                // Seed Monthly Counter
                String yearMonth = String.format("%04d-%02d", counterDate.getYear(), counterDate.getMonthValue());
                MonthlyCounterEntity monthlyCounter = new MonthlyCounterEntity(userId, yearMonth, dailyDebitCount, monthlyDebitAmount);
                monthlyCounterRepository.save(monthlyCounter);

                result.incrementSuccess();
            }
        }

        log.info("Loaded {} users with {} errors from {}", result.getSuccessfullyLoaded(), result.getErrorCount(), usersPath);
        return result;
    }
}

