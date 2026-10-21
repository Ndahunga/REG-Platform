package rw.terimbere.csams.modules.memberpayment.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import rw.terimbere.csams.modules.membercredit.CreditPurpose;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentAllocationType;
import rw.terimbere.csams.modules.memberpayment.entity.PaymentMethod;

/**
 * Input of {@code MemberPaymentService.create}. Deliberately carries no receipt-analysis fields: a payment never
 * needs OCR data to exist, and receipt classifications are not accepted from the submitter.
 *
 * @param memberUserId   the member who paid; {@code null} means the caller. Recording for someone else needs staff
 *                       authority over every allocation type.
 * @param idempotencyKey optional client key; repeating the same request with the same key returns the original
 *                       payment instead of creating another.
 * @param manualReview   true when the payer chooses to continue although the receipt needs a manual review; it is only
 *                       honoured when the server's own reading of the receipt says a review is needed, and it never
 *                       lets a failed or pending receipt through.
 */
public record MemberPaymentCreateCommand(
        UUID memberUserId,
        BigDecimal amount,
        LocalDate paymentDate,
        PaymentMethod paymentMethod,
        String transactionReference,
        String evidenceFileKey,
        String idempotencyKey,
        String notes,
        List<Allocation> allocations,
        Boolean manualReview) {

    /** Without a manual-review request: the payer asked for nothing beyond submitting the payment. */
    public MemberPaymentCreateCommand(
            UUID memberUserId,
            BigDecimal amount,
            LocalDate paymentDate,
            PaymentMethod paymentMethod,
            String transactionReference,
            String evidenceFileKey,
            String idempotencyKey,
            String notes,
            List<Allocation> allocations) {
        this(memberUserId, amount, paymentDate, paymentMethod, transactionReference, evidenceFileKey, idempotencyKey,
                notes, allocations, null);
    }

    /**
     * One target of the payment.
     *
     * @param periodYear  CONTRIBUTION only
     * @param periodMonth CONTRIBUTION only
     * @param loanId      LOAN_REPAYMENT only
     * @param principal   optional loan-repayment breakdown; principal, interest and penalty are all given or all
     *                    omitted, and when given must add up to {@code amount}
     */
    public record Allocation(
            PaymentAllocationType type,
            BigDecimal amount,
            Integer periodYear,
            Integer periodMonth,
            UUID loanId,
            BigDecimal principal,
            BigDecimal interest,
            BigDecimal penalty,
            CreditPurpose creditPurpose) {

        /** An allocation that is not a member credit. */
        public Allocation(
                PaymentAllocationType type,
                BigDecimal amount,
                Integer periodYear,
                Integer periodMonth,
                UUID loanId,
                BigDecimal principal,
                BigDecimal interest,
                BigDecimal penalty) {
            this(type, amount, periodYear, periodMonth, loanId, principal, interest, penalty, null);
        }

        public static Allocation contribution(int year, int month, BigDecimal amount) {
            return new Allocation(PaymentAllocationType.CONTRIBUTION, amount, year, month, null, null, null, null);
        }

        public static Allocation loanRepayment(UUID loanId, BigDecimal amount) {
            return new Allocation(PaymentAllocationType.LOAN_REPAYMENT, amount, null, null, loanId, null, null, null);
        }

        public static Allocation loanRepayment(
                UUID loanId, BigDecimal amount, BigDecimal principal, BigDecimal interest, BigDecimal penalty) {
            return new Allocation(
                    PaymentAllocationType.LOAN_REPAYMENT, amount, null, null, loanId, principal, interest, penalty);
        }

        public static Allocation memberCredit(BigDecimal amount, CreditPurpose purpose, UUID loanId) {
            return new Allocation(PaymentAllocationType.MEMBER_CREDIT, amount, null, null, loanId, null, null, null, purpose);
        }
    }
}
