package com.ps006.upiswitch.command;

import com.ps006.upiswitch.account.repository.AccountRepository;
import com.ps006.upiswitch.command.loader.*;
import com.ps006.upiswitch.common.repository.HolidayRepository;
import com.ps006.upiswitch.common.util.BankingCalendarService;
import com.ps006.upiswitch.counter.repository.DailyCounterRepository;
import com.ps006.upiswitch.merchant.repository.MerchantRepository;
import com.ps006.upiswitch.user.repository.UserRepository;
import com.ps006.upiswitch.vpa.repository.VpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MasterDataLoaderTest {

    @Autowired private HolidayLoader holidayLoader;
    @Autowired private UserLoader userLoader;
    @Autowired private MerchantLoader merchantLoader;
    @Autowired private MasterDataInitializer masterDataInitializer;

    @Autowired private HolidayRepository holidayRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private VpaRepository vpaRepository;
    @Autowired private DailyCounterRepository dailyCounterRepository;
    @Autowired private BankingCalendarService bankingCalendarService;

    @Test
    @DisplayName("Verify successful loading of official sample dataset")
    void testLoadOfficialSampleDataset() throws IOException {
        Path inputDir = Paths.get("sample-data", "T046-PS-006-sample-data");
        assertTrue(Files.exists(inputDir), "Sample data dir must exist");

        MasterDataInitializer.MasterDataReport report = masterDataInitializer.initialize(inputDir);

        assertFalse(report.hasErrors(), "Official sample dataset must have 0 ingestion errors");
        assertEquals(20, report.getTotalLoaded(), "Total loaded records must be 4 holidays + 12 users + 4 merchants = 20");

        assertEquals(4, holidayRepository.count());
        assertEquals(12, userRepository.count());
        assertEquals(4, merchantRepository.count());
        assertEquals(16, accountRepository.count()); // 12 users + 4 merchants
        assertEquals(16, vpaRepository.count());
        assertEquals(12, dailyCounterRepository.count());
    }

    @Test
    @DisplayName("HolidayLoader Negative: Malformed header and bad date format")
    void testHolidayLoaderValidation(@TempDir Path tempDir) throws IOException {
        // Case 1: Bad header
        Path badHeaderFile = tempDir.resolve("bad_header.csv");
        Files.writeString(badHeaderFile, "holiday,title\n2026-10-02,Gandhi Jayanti\n");
        IngestionResult res1 = holidayLoader.load(badHeaderFile);
        assertTrue(res1.hasErrors());
        assertTrue(res1.getErrors().get(0).errorMessage().contains("Invalid header"));

        // Case 2: Malformed date
        Path badDateFile = tempDir.resolve("bad_date.csv");
        Files.writeString(badDateFile, "date,name\n02-10-2026,Gandhi Jayanti\n");
        IngestionResult res2 = holidayLoader.load(badDateFile);
        assertTrue(res2.hasErrors());
        assertTrue(res2.getErrors().get(0).errorMessage().contains("Invalid date format"));

        // Case 3: Duplicate holiday date
        Path dupDateFile = tempDir.resolve("dup_date.csv");
        Files.writeString(dupDateFile, "date,name\n2026-10-02,Gandhi Jayanti\n2026-10-02,Gandhi Jayanti Dup\n");
        IngestionResult res3 = holidayLoader.load(dupDateFile);
        assertTrue(res3.hasErrors());
        assertTrue(res3.getErrors().get(0).errorMessage().contains("Duplicate holiday date"));
    }

    @Test
    @DisplayName("UserLoader Negative: Negative balance, bad VPA, duplicate user_id")
    void testUserLoaderValidation(@TempDir Path tempDir) throws IOException {
        String header = "user_id,name,vpa,bank_account,ifsc,balance,vpa_created_at,device_bound_at,mobile,counter_date,daily_debit_count,daily_debit_amount,monthly_debit_amount\n";

        // Case 1: Negative balance
        Path negBalFile = tempDir.resolve("neg_bal.csv");
        Files.writeString(negBalFile, header + "USR-999,Test,test@upi,123456789012,SBIN001,-100.00,2026-10-01 10:00:00,,9999999999,2026-10-01,0,0.00,0.00\n");
        IngestionResult res1 = userLoader.load(negBalFile);
        assertTrue(res1.hasErrors());
        assertTrue(res1.getErrors().get(0).errorMessage().contains("cannot be negative"));

        // Case 2: Invalid VPA without @
        Path badVpaFile = tempDir.resolve("bad_vpa.csv");
        Files.writeString(badVpaFile, header + "USR-998,Test,invalidvpa,123456789013,SBIN001,100.00,2026-10-01 10:00:00,,9999999999,2026-10-01,0,0.00,0.00\n");
        IngestionResult res2 = userLoader.load(badVpaFile);
        assertTrue(res2.hasErrors());
        assertTrue(res2.getErrors().get(0).errorMessage().contains("must contain '@'"));

        // Case 3: Duplicate user_id
        Path dupUserFile = tempDir.resolve("dup_user.csv");
        Files.writeString(dupUserFile, header +
                "USR-997,Test 1,test1@upi,123456789014,SBIN001,100.00,2026-10-01 10:00:00,,9999999999,2026-10-01,0,0.00,0.00\n" +
                "USR-997,Test 2,test2@upi,123456789015,SBIN001,100.00,2026-10-01 10:00:00,,9999999998,2026-10-01,0,0.00,0.00\n");
        IngestionResult res3 = userLoader.load(dupUserFile);
        assertTrue(res3.hasErrors());
        assertTrue(res3.getErrors().get(0).errorMessage().contains("Duplicate user_id"));
    }

    @Test
    @DisplayName("MerchantLoader Negative: Invalid MCC, bad verified boolean, duplicate merchant_id")
    void testMerchantLoaderValidation(@TempDir Path tempDir) throws IOException {
        String header = "merchant_id,name,vpa,mcc,verified,bank_account,balance\n";

        // Case 1: MCC not 4 digits
        Path badMccFile = tempDir.resolve("bad_mcc.csv");
        Files.writeString(badMccFile, header + "MER-999,Shop,shop@icici,123,true,999999999999,500.00\n");
        IngestionResult res1 = merchantLoader.load(badMccFile);
        assertTrue(res1.hasErrors());
        assertTrue(res1.getErrors().get(0).errorMessage().contains("Invalid MCC"));

        // Case 2: Verified boolean invalid
        Path badVerifiedFile = tempDir.resolve("bad_verified.csv");
        Files.writeString(badVerifiedFile, header + "MER-998,Shop,shop2@icici,5411,yes,999999999998,500.00\n");
        IngestionResult res2 = merchantLoader.load(badVerifiedFile);
        assertTrue(res2.hasErrors());
        assertTrue(res2.getErrors().get(0).errorMessage().contains("Invalid verified boolean"));
    }
}

