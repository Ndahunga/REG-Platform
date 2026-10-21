package rw.terimbere.csams.modules.memberpayment.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPayment;
import rw.terimbere.csams.modules.memberpayment.entity.MemberPaymentPosting;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentAllocationType;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentStatus;
import rw.terimbere.csams.modules.memberpayment.entity.PostingAttribution;
import rw.terimbere.csams.modules.memberpayment.entity.VerificationMethod;

/** Staff view of where a payment stands on the way to the books and what each allocation did there. */
public record PaymentPostingResponse(
        UUID paymentId,
        PaymentStatus status,
        BigDecimal amount,
        VerificationMethod verificationMethod,
        boolean verificationEvidenceRecorded,
        UUID verifiedBy,
        Instant verifiedAt,
        UUID evidenceCompletedBy,
        Instant evidenceCompletedAt,
        UUID postedBy,
        Instant postedAt,
        int postingAttempts,
        String lastPostingFailureCode,
        Instant lastPostingFailedAt,
        /** True when the payment is verified, has evidence and a failed attempt: a retry is the next step. */
        boolean retryable,
        List<Line> postings) {

    public record Line(
            UUID allocationId,
            PaymentAllocationType type,
            BigDecimal amount,
            UUID contributionId,
            Integer periodYear,
            Integer periodMonth,
            BigDecimal paidBefore,
            BigDecimal paidAfter,
            UUID ledgerEntryId,
            PostingAttribution attribution,
            String operatingModel,
            Integer rotatingBps,
            Integer retainedBps,
            BigDecimal rotatingAmount,
            BigDecimal retainedAmount,
            UUID rotationPeriodId,
            UUID originalRecipientUserId,
            UUID loanId,
            UUID loanRepaymentId,
            BigDecimal principalAmount,
            BigDecimal interestAmount,
            BigDecimal penaltyAmount) {

        static Line from(MemberPaymentPosting p) {
            return new Line(
                    p.getAllocationId(),
                    p.getAllocationType(),
                    p.getAmount(),
                    p.getContributionId(),
                    p.getPeriodYear(),
                    p.getPeriodMonth(),
                    p.getPaidBefore(),
                    p.getPaidAfter(),
                    p.getLedgerEntryId(),
                    p.getAttribution(),
                    p.getOperatingModel(),
                    p.getRotatingBps(),
                    p.getRetainedBps(),
                    p.getRotatingAmount(),
                    p.getRetainedAmount(),
                    p.getRotationPeriodId(),
                    p.getOriginalRecipientUserId(),
                    p.getLoanId(),
                    p.getLoanRepaymentId(),
                    p.getPrincipalAmount(),
                    p.getInterestAmount(),
                    p.getPenaltyAmount());
        }
    }

    public static PaymentPostingResponse from(MemberPayment p, List<MemberPaymentPosting> postings) {
        boolean retryable = p.getStatus() == PaymentStatus.VERIFIED
                && p.hasVerificationEvidence()
                && p.getPostingAttempts() > 0;
        return new PaymentPostingResponse(
                p.getId(),
                p.getStatus(),
                p.getAmount(),
                p.getVerificationMethod(),
                p.hasVerificationEvidence(),
                p.getVerifiedBy(),
                p.getVerifiedAt(),
                p.getEvidenceCompletedBy(),
                p.getEvidenceCompletedAt(),
                p.getPostedBy(),
                p.getPostedAt(),
                p.getPostingAttempts(),
                p.getLastPostingFailureCode(),
                p.getLastPostingFailedAt(),
                retryable,
                postings.stream().map(Line::from).toList());
    }
}
