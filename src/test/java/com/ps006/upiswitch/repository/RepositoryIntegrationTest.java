package com.ps006.upiswitch.repository;

import com.ps006.upiswitch.account.entity.AccountEntity;
import com.ps006.upiswitch.account.repository.AccountRepository;
import com.ps006.upiswitch.counter.entity.DailyCounterEntity;
import com.ps006.upiswitch.counter.repository.DailyCounterRepository;
import com.ps006.upiswitch.ledger.entity.LedgerEntryEntity;
import com.ps006.upiswitch.ledger.model.LegType;
import com.ps006.upiswitch.ledger.repository.LedgerRepository;
import com.ps006.upiswitch.merchant.entity.MerchantEntity;
import com.ps006.upiswitch.merchant.repository.MerchantRepository;
import com.ps006.upiswitch.payment.entity.TransactionEntity;
import com.ps006.upiswitch.payment.model.TransactionStatus;
import com.ps006.upiswitch.payment.model.TransactionType;
import com.ps006.upiswitch.payment.repository.TransactionRepository;
import com.ps006.upiswitch.user.entity.UserEntity;
import com.ps006.upiswitch.user.repository.UserRepository;
import com.ps006.upiswitch.vpa.entity.VpaEntity;
import com.ps006.upiswitch.vpa.model.OwnerType;
import com.ps006.upiswitch.vpa.repository.VpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RepositoryIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private VpaRepository vpaRepository;
    @Autowired private DailyCounterRepository dailyCounterRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private LedgerRepository ledgerRepository;

    @Test
    @DisplayName("Verify User, Account, and VPA persistence and retrieval")
    void testUserAccountVpaPersistence() {
        // 1. Create User
        UserEntity user = new UserEntity("USR-TEST-001", "Test User", "9999999999", LocalDateTime.now(), LocalDateTime.now());
        userRepository.save(user);

        // 2. Create Account
        AccountEntity account = new AccountEntity(
                null, "ACC9990001", "SBIN0001", "USR-TEST-001", null,
                new BigDecimal("5000.00"), "INR", 0L, LocalDateTime.now(), LocalDateTime.now()
        );
        AccountEntity savedAccount = accountRepository.save(account);
        assertNotNull(savedAccount.getAccountId());

        // 3. Create VPA
        VpaEntity vpa = new VpaEntity("test@oksbi", savedAccount.getAccountId(), OwnerType.USER, "USR-TEST-001", LocalDateTime.now(), true);
        vpaRepository.save(vpa);

        // Assertions
        Optional<UserEntity> userOpt = userRepository.findById("USR-TEST-001");
        assertTrue(userOpt.isPresent());
        assertEquals("Test User", userOpt.get().getName());

        Optional<AccountEntity> accOpt = accountRepository.findByUserId("USR-TEST-001");
        assertTrue(accOpt.isPresent());
        assertEquals(new BigDecimal("5000.00"), accOpt.get().getBalance());

        Optional<VpaEntity> vpaOpt = vpaRepository.findByVpa("test@oksbi");
        assertTrue(vpaOpt.isPresent());
        assertEquals(savedAccount.getAccountId(), vpaOpt.get().getAccountId());
    }

    @Test
    @DisplayName("Verify Transaction and Immutable Ledger Entry persistence")
    void testTransactionAndLedgerPersistence() {
        // Save transaction
        TransactionEntity txn = new TransactionEntity(
                "TXN-REPO-001", "000000000099", "PAY", "payer@okaxis", "payee@oksbi",
                new BigDecimal("1000.00"), "test remark", TransactionType.P2P,
                TransactionStatus.SUCCESS, null, BigDecimal.ZERO,
                LocalDateTime.now(), null, null
        );
        transactionRepository.save(txn);

        // Save ledger entry
        LedgerEntryEntity entry = new LedgerEntryEntity(
                1L, "TXN-REPO-001", 100L, "payer@okaxis", LegType.DEBIT,
                new BigDecimal("1000.00"), null, new BigDecimal("4000.00"),
                LocalDateTime.now(), null, "Debit test"
        );
        ledgerRepository.save(entry);

        Optional<TransactionEntity> txnOpt = transactionRepository.findByTxnId("TXN-REPO-001");
        assertTrue(txnOpt.isPresent());
        assertEquals(TransactionStatus.SUCCESS, txnOpt.get().getStatus());

        Optional<LedgerEntryEntity> entryOpt = ledgerRepository.findTopByOrderBySequenceNumberDesc();
        assertTrue(entryOpt.isPresent());
        assertEquals(1L, entryOpt.get().getSequenceNumber());
        assertEquals(new BigDecimal("1000.00"), entryOpt.get().getDebitAmount());
    }

    @Test
    @DisplayName("Verify Daily Counter persistence and query")
    void testDailyCounterPersistence() {
        DailyCounterEntity counter = new DailyCounterEntity(
                "USR-REPO-001", LocalDate.of(2026, 10, 1), 5, new BigDecimal("25000.00")
        );
        dailyCounterRepository.save(counter);

        Optional<DailyCounterEntity> opt = dailyCounterRepository.findByUserIdAndCounterDate(
                "USR-REPO-001", LocalDate.of(2026, 10, 1)
        );
        assertTrue(opt.isPresent());
        assertEquals(5, opt.get().getDebitCount());
        assertEquals(new BigDecimal("25000.00"), opt.get().getDebitAmount());
    }
}

