package rw.terimbere.csams.modules.memberpayment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPayment;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentAllocationType;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentStatus;

/**
 * One row of a payment list. Deliberately small: no allocations, no staff identities, no receipt-analysis
 * metadata. {@code memberName} is only filled for staff views.
 */
public record MemberPaymentSummaryResponse(
        UUID id,
        UUID memberUserId,
        String memberName,
        BigDecimal amount,
        String currency,
        LocalDate paymentDate,
        PaymentMethod paymentMethod,
        String transactionReference,
        PaymentStatus status,
        Instant submittedAt,
        List<PaymentAllocationType> allocationTypes) {

    public static MemberPaymentSummaryResponse from(
            MemberPayment p, List<PaymentAllocationType> allocationTypes, String memberName) {
        return new MemberPaymentSummaryResponse(
                p.getId(),
                p.getMemberUserId(),
                memberName,
                p.getAmount(),
                p.getCurrency(),
                p.getPaymentDate(),
                p.getPaymentMethod(),
                p.getTransactionReference(),
                p.getStatus(),
                p.getSubmittedAt(),
                allocationTypes);
    }
}
