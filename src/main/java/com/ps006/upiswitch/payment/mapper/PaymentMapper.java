package com.ps006.upiswitch.payment.mapper;

import com.ps006.upiswitch.payment.entity.TransactionEntity;
import com.ps006.upiswitch.payment.model.TransactionDetails;
import org.springframework.stereotype.Component;

@Component
public class PaymentMapper {

    public TransactionDetails toDomain(TransactionEntity entity) {
        if (entity == null) return null;
        return new TransactionDetails(
                entity.getTxnId(),
                entity.getRrn(),
                entity.getCommand(),
                entity.getPayerVpa(),
                entity.getPayeeVpa(),
                entity.getAmount(),
                entity.getRemarks(),
                entity.getTransactionType(),
                entity.getStatus(),
                entity.getRuleIds(),
                entity.getRefundedAmount(),
                entity.getInitiatedAt(),
                entity.getReversedAt(),
                entity.getDeemedSuccessAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}

