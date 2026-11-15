package com.ps006.upiswitch.account.mapper;

import com.ps006.upiswitch.account.entity.AccountEntity;
import com.ps006.upiswitch.account.model.AccountDetails;
import org.springframework.stereotype.Component;

@Component
public class AccountMapper {

    public AccountDetails toDomain(AccountEntity entity) {
        if (entity == null) return null;
        return new AccountDetails(
                entity.getAccountId(),
                entity.getAccountNumber(),
                entity.getIfsc(),
                entity.getUserId(),
                entity.getMerchantId(),
                entity.getBalance(),
                entity.getCurrency(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public AccountEntity toEntity(AccountDetails details) {
        if (details == null) return null;
        return new AccountEntity(
                details.accountId(),
                details.accountNumber(),
                details.ifsc(),
                details.userId(),
                details.merchantId(),
                details.balance(),
                details.currency(),
                details.version(),
                details.createdAt(),
                details.updatedAt()
        );
    }
}

