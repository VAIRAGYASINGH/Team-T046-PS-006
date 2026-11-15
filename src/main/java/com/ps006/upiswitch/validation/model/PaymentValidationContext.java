package com.ps006.upiswitch.validation.model;

import com.ps006.upiswitch.account.model.AccountDetails;
import com.ps006.upiswitch.counter.model.DailyCounterModel;
import com.ps006.upiswitch.merchant.model.MerchantProfile;
import com.ps006.upiswitch.payment.entity.BeneficiaryPaymentHistoryEntity;
import com.ps006.upiswitch.payment.model.TransactionType;
import com.ps006.upiswitch.user.model.UserProfile;
import com.ps006.upiswitch.vpa.model.VpaDetails;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentValidationContext(
        String txnId,
        String payerVpa,
        String payeeVpa,
        BigDecimal amount,
        LocalDateTime timestamp,
        TransactionType transactionType,
        VpaDetails payerVpaDetails,
        VpaDetails payeeVpaDetails,
        UserProfile payerUser,
        MerchantProfile payeeMerchant,
        AccountDetails payerAccount,
        DailyCounterModel payerDailyCounter,
        BeneficiaryPaymentHistoryEntity beneficiaryHistory
) {}

