package rw.terimbere.csams.modules.memberpayment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPayment;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentAllocation;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentAllocationType;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentStatus;
import rw.terimbere.csams.modules.memberpayment.entity.ReceiptStatus;

public record MemberPaymentResponse(
        UUID id,
        UUID cooperativeId,
        UUID memberUserId,
        BigDecimal amount,
        BigDecimal allocatedAmount,
        String currency,
        LocalDate paymentDate,
        PaymentMethod paymentMethod,
        String transactionReference,
        String evidenceFileKey,
        String notes,
        PaymentStatus status,
        UUID submittedBy,
        Instant submittedAt,
        UUID verifiedBy,
        Instant verifiedAt,
        UUID postedBy,
        Instant postedAt,
        String rejectionReason,
        String reversalReason,
        BigDecimal extractedAmount,
        String extractedReference,
        ReceiptStatus receiptStatus,
        BigDecimal receiptConfidence,
        boolean receiptReviewRequested,
        String receiptRecipientMatch,
        boolean receiptAmountAmbiguous,
        List<AllocationResponse> allocations) {

    public record AllocationResponse(
            UUID id,
            PaymentAllocationType type,
            BigDecimal amount,
            Integer periodYear,
            Integer periodMonth,
            UUID loanId,
            rw.terimbere.csams.modules.membercredit.CreditPurpose creditPurpose,
            BigDecimal principalAmount,
            BigDecimal interestAmount,
            BigDecimal penaltyAmount) {

        static AllocationResponse from(MemberPaymentAllocation a) {
            return new AllocationResponse(
                    a.getId(),
                    a.getAllocationType(),
                    a.getAmount(),
                    a.getPeriodYear(),
                    a.getPeriodMonth(),
                    a.getLoanId(),
                    a.getCreditPurpose(),
                    a.getPrincipalAmount(),
                    a.getInterestAmount(),
                    a.getPenaltyAmount());
        }
    }

    /**
     * The payer's view: no verifier/poster identities, no staff identity that is not their own, and none of the
     * internal receipt-analysis metadata. Staff see the full record.
     */
    public MemberPaymentResponse forMember(UUID viewerUserId) {
        return new MemberPaymentResponse(
                id,
                cooperativeId,
                memberUserId,
                amount,
                allocatedAmount,
                currency,
                paymentDate,
                paymentMethod,
                transactionReference,
                evidenceFileKey,
                notes,
                status,
                viewerUserId != null && viewerUserId.equals(submittedBy) ? submittedBy : null,
                submittedAt,
                null,
                verifiedAt,
                null,
                postedAt,
                rejectionReason,
                reversalReason,
                null,
                null,
                null,
                null,
                receiptReviewRequested,
                null,
                false,
                allocations);
    }

    public static MemberPaymentResponse from(MemberPayment p, List<MemberPaymentAllocation> allocations) {
        return new MemberPaymentResponse(
                p.getId(),
                p.getCooperativeId(),
                p.getMemberUserId(),
                p.getAmount(),
                p.getAllocatedAmount(),
                p.getCurrency(),
                p.getPaymentDate(),
                p.getPaymentMethod(),
                p.getTransactionReference(),
                p.getEvidenceFileKey(),
                p.getNotes(),
                p.getStatus(),
                p.getSubmittedBy(),
                p.getSubmittedAt(),
                p.getVerifiedBy(),
                p.getVerifiedAt(),
                p.getPostedBy(),
                p.getPostedAt(),
                p.getRejectionReason(),
                p.getReversalReason(),
                p.getExtractedAmount(),
                p.getExtractedReference(),
                p.getReceiptStatus(),
                p.getReceiptConfidence(),
                p.isReceiptReviewRequested(),
                p.getReceiptRecipientMatch(),
                p.isReceiptAmountAmbiguous(),
                allocations.stream().map(AllocationResponse::from).toList());
    }
}
