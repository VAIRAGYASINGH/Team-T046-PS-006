package com.ps006.upiswitch.command.loader;

import com.ps006.upiswitch.account.entity.AccountEntity;
import com.ps006.upiswitch.account.service.AccountService;
import com.ps006.upiswitch.merchant.entity.MerchantEntity;
import com.ps006.upiswitch.merchant.service.MerchantService;
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
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class MerchantLoader {

    private static final Logger log = LoggerFactory.getLogger(MerchantLoader.class);
    private static final String EXPECTED_HEADER = "merchant_id,name,vpa,mcc,verified,bank_account,balance";
    private static final Pattern MCC_PATTERN = Pattern.compile("^\\d{4}$");

    private final MerchantService merchantService;
    private final AccountService accountService;
    private final VpaRepository vpaRepository;

    public MerchantLoader(MerchantService merchantService,
                          AccountService accountService,
                          VpaRepository vpaRepository) {
        this.merchantService = merchantService;
        this.accountService = accountService;
        this.vpaRepository = vpaRepository;
    }

    @Transactional
    public IngestionResult load(Path merchantsPath) throws IOException {
        IngestionResult result = new IngestionResult("merchants.csv");
        if (!Files.exists(merchantsPath)) {
            result.addError(0, "", "Merchants file not found at " + merchantsPath);
            return result;
        }

        Set<String> seenMerchantIds = new HashSet<>();
        Set<String> seenVpas = new HashSet<>();
        Set<String> seenBankAccounts = new HashSet<>();

        try (BufferedReader reader = Files.newBufferedReader(merchantsPath, StandardCharsets.UTF_8)) {
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
                if (parts.length != 7) {
                    result.addError(lineNo, trimmed, String.format("Expected 7 columns, found %d", parts.length));
                    continue;
                }

                String merchantId = parts[0].trim();
                String name = parts[1].trim();
                String vpa = parts[2].trim();
                String mcc = parts[3].trim();
                String verifiedStr = parts[4].trim();
                String bankAccount = parts[5].trim();
                String balanceStr = parts[6].trim();

                // Validate required fields
                if (merchantId.isEmpty() || name.isEmpty() || vpa.isEmpty() || mcc.isEmpty() || bankAccount.isEmpty()) {
                    result.addError(lineNo, trimmed, "Required identifier/account fields cannot be blank");
                    continue;
                }

                if (!vpa.contains("@")) {
                    result.addError(lineNo, trimmed, "Invalid VPA format (must contain '@'): " + vpa);
                    continue;
                }

                if (!MCC_PATTERN.matcher(mcc).matches()) {
                    result.addError(lineNo, trimmed, "Invalid MCC (must be exactly 4 digits): " + mcc);
                    continue;
                }

                boolean verified;
                if ("true".equalsIgnoreCase(verifiedStr)) {
                    verified = true;
                } else if ("false".equalsIgnoreCase(verifiedStr)) {
                    verified = false;
                } else {
                    result.addError(lineNo, trimmed, "Invalid verified boolean (must be 'true' or 'false'): " + verifiedStr);
                    continue;
                }

                // Duplicate validation
                if (seenMerchantIds.contains(merchantId)) {
                    result.addError(lineNo, trimmed, "Duplicate merchant_id detected: " + merchantId);
                    continue;
                }
                if (seenVpas.contains(vpa)) {
                    result.addError(lineNo, trimmed, "Duplicate merchant VPA detected: " + vpa);
                    continue;
                }
                if (seenBankAccounts.contains(bankAccount)) {
                    result.addError(lineNo, trimmed, "Duplicate bank_account detected: " + bankAccount);
                    continue;
                }

                // Balance validation
                BigDecimal balance;
                try {
                    balance = new BigDecimal(balanceStr).setScale(2, RoundingMode.UNNECESSARY);
                    if (balance.compareTo(BigDecimal.ZERO) < 0) {
                        result.addError(lineNo, trimmed, "Merchant opening balance cannot be negative: " + balanceStr);
                        continue;
                    }
                } catch (NumberFormatException | ArithmeticException e) {
                    result.addError(lineNo, trimmed, "Invalid monetary balance format: " + balanceStr);
                    continue;
                }

                seenMerchantIds.add(merchantId);
                seenVpas.add(vpa);
                seenBankAccounts.add(bankAccount);

                LocalDateTime now = LocalDateTime.now();

                // Persist Merchant Entity
                MerchantEntity merchant = new MerchantEntity(merchantId, name, mcc, verified, now);
                merchantService.saveEntity(merchant);

                // Persist Account Entity (linked to merchant)
                AccountEntity account = new AccountEntity(
                        null, bankAccount, "ICIC0000001", null, merchantId, balance, "INR", 0L, now, now
                );
                account = accountService.saveEntity(account);

                // Persist VPA Entity
                VpaEntity vpaEntity = new VpaEntity(vpa, account.getAccountId(), OwnerType.MERCHANT, merchantId, now, true);
                vpaRepository.save(vpaEntity);

                result.incrementSuccess();
            }
        }

        log.info("Loaded {} merchants with {} errors from {}", result.getSuccessfullyLoaded(), result.getErrorCount(), merchantsPath);
        return result;
    }
}

