package com.ps006.upiswitch.account.service;

import com.ps006.upiswitch.account.entity.AccountEntity;
import com.ps006.upiswitch.account.mapper.AccountMapper;
import com.ps006.upiswitch.account.model.AccountDetails;
import com.ps006.upiswitch.account.repository.AccountRepository;
import com.ps006.upiswitch.common.exception.EntityNotFoundException;
import com.ps006.upiswitch.common.exception.InsufficientFundsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class AccountService {

    private final AccountRepository accountRepository;
    private final AccountMapper accountMapper;

    public AccountService(AccountRepository accountRepository, AccountMapper accountMapper) {
        this.accountRepository = accountRepository;
        this.accountMapper = accountMapper;
    }

    @Transactional
    public AccountDetails saveAccount(AccountDetails details) {
        AccountEntity entity = accountMapper.toEntity(details);
        AccountEntity saved = accountRepository.save(entity);
        return accountMapper.toDomain(saved);
    }

    @Transactional
    public AccountEntity saveEntity(AccountEntity entity) {
        return accountRepository.save(entity);
    }

    public Optional<AccountDetails> findById(Long accountId) {
        return accountRepository.findById(accountId).map(accountMapper::toDomain);
    }

    public AccountDetails getAccountOrThrow(Long accountId) {
        return findById(accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account", String.valueOf(accountId)));
    }

    public Optional<AccountDetails> findByUserId(String userId) {
        return accountRepository.findByUserId(userId).map(accountMapper::toDomain);
    }

    public Optional<AccountDetails> findByMerchantId(String merchantId) {
        return accountRepository.findByMerchantId(merchantId).map(accountMapper::toDomain);
    }

    public List<AccountDetails> findAllAccounts() {
        return accountRepository.findAll().stream()
                .map(accountMapper::toDomain)
                .collect(Collectors.toList());
    }

    @Transactional
    public AccountEntity debitAccount(Long accountId, BigDecimal amount) {
        BigDecimal standardizedAmount = amount.setScale(2, RoundingMode.UNNECESSARY);
        AccountEntity account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account", String.valueOf(accountId)));

        if (account.getBalance().compareTo(standardizedAmount) < 0) {
            throw new InsufficientFundsException(
                    account.getAccountNumber(), account.getBalance(), standardizedAmount
            );
        }

        account.setBalance(account.getBalance().subtract(standardizedAmount));
        return accountRepository.save(account);
    }

    @Transactional
    public AccountEntity creditAccount(Long accountId, BigDecimal amount) {
        BigDecimal standardizedAmount = amount.setScale(2, RoundingMode.UNNECESSARY);
        AccountEntity account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account", String.valueOf(accountId)));

        account.setBalance(account.getBalance().add(standardizedAmount));
        return accountRepository.save(account);
    }

    public BigDecimal getBalance(Long accountId) {
        return accountRepository.findById(accountId)
                .map(AccountEntity::getBalance)
                .orElseThrow(() -> new EntityNotFoundException("Account", String.valueOf(accountId)));
    }

    public AccountEntity getEntityForUpdate(Long accountId) {
        return accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account", String.valueOf(accountId)));
    }
}

